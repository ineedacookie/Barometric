package com.barometric.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.work.*
import com.barometric.app.BarometricApplication
import com.barometric.app.MainActivity
import com.barometric.app.PressureRefreshWorker
import com.barometric.app.R
import com.barometric.app.data.*
import com.barometric.app.domain.Pressure
import com.barometric.app.ui.pressureText
import com.barometric.app.ui.sourceColor
import com.barometric.app.ui.sourceLabel
import com.barometric.app.ui.pressureBasis
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.max

// Widget configuration is independent for each placement; weather uses the app's location and units.
enum class WidgetMode(val label: String) { STATS("High, low, difference"), GRAPH("Pressure graph"), BOTH("Both") }

object WidgetSettings {
    private fun store(context: Context) = context.getSharedPreferences("pressure_widgets", Context.MODE_PRIVATE)
    fun mode(context: Context, id: Int): WidgetMode = WidgetMode.entries.find { it.name == store(context).getString("mode-$id", null) } ?: WidgetMode.BOTH
    fun set(context: Context, id: Int, mode: WidgetMode) { store(context).edit().putString("mode-$id", mode.name).apply() }
    fun remove(context: Context, ids: IntArray) { store(context).edit().apply { ids.forEach { remove("mode-$it") } }.apply() }
}

class PressureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, ids)
        requestRefresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { render(context, intArrayOf(id)) }
    override fun onDeleted(context: Context, ids: IntArray) { WidgetSettings.remove(context, ids) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            render(context, ids(context)); requestRefresh(context)
        }
    }
    private fun render(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { update(context, ids) }
            catch (e: Exception) { if (e is CancellationException) throw e; Log.w("PressureWidget", "Widget update unavailable", e) }
            finally { pending.finish() }
        }
    }
    companion object {
        private fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PressureWidget::class.java))
        suspend fun updateAll(context: Context) {
            val ids = ids(context)
            if (ids.isNotEmpty()) try { update(context, ids) }
            catch (e: Exception) { if (e is CancellationException) throw e; Log.w("PressureWidget", "Widget update unavailable", e) }
        }
        fun requestRefresh(context: Context) {
            if (ids(context).isEmpty()) return
            val work = OneTimeWorkRequestBuilder<PressureRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("widget-pressure-refresh", ExistingWorkPolicy.KEEP, work)
        }
        suspend fun update(context: Context, ids: IntArray) = withContext(Dispatchers.IO) {
            val container = (context.applicationContext as BarometricApplication).container
            val location = container.tracker.currentLocation()
            val settings = container.settings.flow.first()
            val today = LocalDate.now(location.zone)
            val start = today.atStartOfDay(location.zone).toEpochSecond()
            val end = today.plusDays(1).atStartOfDay(location.zone).toEpochSecond()
            val sources = container.tracker.dao.sources(location.id).filter { it.isActive }
            val readings = container.tracker.dao.readings(location.id, start, end)
            val summaries = Pressure.summarize(location, sources, readings)
            val summary = Pressure.preferred(summaries)
            val latest = summaries.flatMap { it.readings }.maxOfOrNull { it.timestamp }
            val manager = AppWidgetManager.getInstance(context)
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ids.forEach { id ->
                val mode = WidgetSettings.mode(context, id)
                val views = RemoteViews(context.packageName, R.layout.pressure_widget)
                views.setTextViewText(R.id.widget_location, location.name)
                views.setTextViewText(R.id.widget_date, "Today · so far · ${settings.unit.label}")
                views.setViewVisibility(R.id.widget_stats, if (mode == WidgetMode.GRAPH) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widget_graph_section, if (mode == WidgetMode.STATS) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.widget_high, summary?.let { pressureText(it.high, settings.unit) } ?: "—")
                views.setTextViewText(R.id.widget_low, summary?.let { pressureText(it.low, settings.unit) } ?: "—")
                views.setTextViewText(R.id.widget_difference, summary?.let { pressureText(it.difference, settings.unit) } ?: "—")
                views.setTextViewText(R.id.widget_stats_source, summary?.let { "${sourceLabel(it.source)} · ${pressureBasis(it.source)}" } ?: "No saved readings")
                views.setTextViewText(R.id.widget_updated, latest?.let {
                    "Readings through ${Instant.ofEpochSecond(it).atZone(location.zone).format(DateTimeFormatter.ofPattern("h:mm a"))}"
                } ?: "Open app to load pressure")
                if (mode != WidgetMode.STATS) {
                    views.setImageViewBitmap(R.id.widget_graph, plot(summaries, start, end, dark))
                    views.setContentDescription(R.id.widget_graph, "Today's pressure graph. ${summaries.joinToString { "${sourceLabel(it.source)}, high ${pressureText(it.high, settings.unit)}, low ${pressureText(it.low, settings.unit)}, difference ${pressureText(it.difference, settings.unit)} ${settings.unit.label}" }}. Tap to inspect in the app.")
                    // Colored labels match the app and graph; labels remain readable without color.
                    listOf("USU", "NOAA", "OPEN_METEO").zip(listOf(R.id.widget_usu, R.id.widget_noaa, R.id.widget_modeled)).forEach { (provider, viewId) ->
                        val source = sources.find { it.provider == provider }
                        views.setViewVisibility(viewId, if (source == null) View.GONE else View.VISIBLE)
                        if (source != null) {
                            views.setTextColor(viewId, sourceColor(source, dark).toArgb())
                            views.setTextViewText(viewId, "${sourceLabel(source)} · ${pressureBasis(source)}")
                        }
                    }
                    views.setTextViewText(R.id.widget_updated, "Separate pressure scales · tap to inspect")
                }
                views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                views.setOnClickPendingIntent(R.id.widget_configure, PendingIntent.getActivity(context, id,
                    Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                manager.updateAppWidget(id, views)
            }
        }
        private fun plot(summaries: List<DaySummary>, start: Long, end: Long, dark: Boolean): Bitmap {
            val bitmap = Bitmap.createBitmap(640, 200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val readings = summaries.flatMap { it.readings }
            val axes = Pressure.chartAxes(readings)
            fun x(time: Long) = 12f + (time - start).toFloat() / (end - start) * 616f
            fun y(reading: PressureReading): Float {
                val axis = axes.getValue(reading.kind)
                return 188f - ((reading.hpa - axis.start) / (axis.endInclusive - axis.start)).toFloat() * 176f
            }
            paint.color = if (dark) 0xFF394B4D.toInt() else 0xFFD9E0DD.toInt()
            paint.strokeWidth = 1f
            repeat(4) { val yy = 12f + it * 176f / 3; canvas.drawLine(12f, yy, 628f, yy, paint) }
            summaries.forEach { day ->
                paint.color = sourceColor(day.source, dark).toArgb(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                val path = Path()
                var previous: PressureReading? = null
                day.readings.forEach { reading ->
                    val old = previous
                    if (old == null || reading.timestamp - old.timestamp > max(120L, max(reading.intervalMinutes, old.intervalMinutes) * 120L)) {
                        path.moveTo(x(reading.timestamp), y(reading))
                        canvas.drawCircle(x(reading.timestamp), y(reading), 2f, paint)
                    } else path.lineTo(x(reading.timestamp), y(reading))
                    previous = reading
                }
                canvas.drawPath(path, paint)
            }
            return bitmap
        }
    }
}
