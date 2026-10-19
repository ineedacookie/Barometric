package com.barometric.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.barometric.app.data.*
import com.barometric.app.domain.Pressure
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

fun pressureText(hpa: Double, unit: PressureUnit): String = String.format(Locale.getDefault(), if (unit == PressureUnit.HPA) "%.1f" else "%.2f", Pressure.convert(hpa, unit))
fun sourceLabel(source: SourceEntity): String = when (source.provider) { "USU" -> "USU"; "NOAA" -> "NOAA"; else -> "Modeled" }
fun pressureBasis(source: SourceEntity): String = if (source.pressureKind == PressureKind.STATION) "station" else "sea level"
fun sourceColor(source: SourceEntity, dark: Boolean = false): Color = Color(when (source.provider) {
    "USU" -> if (dark) 0xFF78C9EE else 0xFF146D9C
    "NOAA" -> if (dark) 0xFFFFBE77 else 0xFFAD5800
    else -> if (dark) 0xFFD1A5F2 else 0xFF8854B5
})

private data class ChartSeries(val source: SourceEntity, val readings: List<PressureReading>) {
    val timestamps = readings.map { it.timestamp }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PressureChart(
    summaries: List<DaySummary>, location: LocationEntity, unit: PressureUnit, range: ChartRange,
    anchor: LocalDate, selectedDate: LocalDate, onDateSelected: (LocalDate) -> Unit,
    sources: List<SourceEntity> = summaries.map { it.source }.distinctBy { it.id },
) {
    val firstDate = anchor.minusDays(range.days - 1)
    val series = remember(summaries, firstDate, anchor, location.id) {
        summaries.filter { it.source.locationId == location.id && it.date in firstDate..anchor }
            .groupBy { it.source.id }.values.map { days ->
                ChartSeries(days.first().source, days.flatMap { it.readings }.distinctBy { it.timestamp }.sortedBy { it.timestamp })
            }.sortedBy { it.source.priority }
    }
    val timeline = remember(series) { series.flatMap { it.readings }.map { it.timestamp }.distinct().sorted() }
    var selectedTime by remember(location.id, range, anchor) { mutableStateOf<Long?>(null) }
    val defaultTime = remember(timeline, selectedDate, location.zone) {
        timeline.lastOrNull { Instant.ofEpochSecond(it).atZone(location.zone).toLocalDate() == selectedDate } ?: timeline.lastOrNull()
    }
    val time = selectedTime ?: defaultTime
    val index = time?.let { Pressure.nearestIndex(timeline, it.toDouble()) } ?: 0
    val selected = series.associate { line ->
        line.source.id to time?.let { t -> Pressure.nearestIndex(line.timestamps, t.toDouble())?.let { line.readings[it] }
            ?.takeIf { abs(it.timestamp - t) <= max(120L, it.intervalMinutes * 120L) } }
    }
    val colors = MaterialTheme.colorScheme
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val start = firstDate.atStartOfDay(location.zone).toEpochSecond()
    val end = anchor.plusDays(1).atStartOfDay(location.zone).toEpochSecond()
    val axes = remember(series) { Pressure.chartAxes(series.flatMap { it.readings }) }
    val primary = if (PressureKind.STATION in axes) PressureKind.STATION else PressureKind.SEA_LEVEL
    val secondary = PressureKind.SEA_LEVEL.takeIf { axes.size > 1 }
    val density = LocalDensity.current
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val textSize = with(density) { 10.sp.toPx() }
    val labelSize = with(density) { 11.sp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    paint.textSize = textSize
    val axisWidth = max(with(density) { 42.dp.toPx() }, axes.values.maxOfOrNull { paint.measureText(pressureText(it.endInclusive, unit)) + margin } ?: 0f)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val rightInset = if (secondary != null) axisWidth else margin
    val linePaths = remember(series, axes, start, end, axisWidth, rightInset, canvasSize, density.density) {
        val left = axisWidth
        val right = max(left + 1, canvasSize.width - rightInset)
        val top = with(density) { 18.dp.toPx() }
        val bottom = canvasSize.height - with(density) { 62.dp.toPx() }
        fun x(timestamp: Long) = left + ((timestamp - start).toDouble() / (end - start)).toFloat() * (right - left)
        fun y(reading: PressureReading): Float {
            val axis = axes.getValue(reading.kind)
            return bottom - ((reading.hpa - axis.start) / (axis.endInclusive - axis.start)).toFloat() * (bottom - top)
        }
        series.associate { line ->
            val path = Path()
            var previous: PressureReading? = null
            line.readings.forEach { reading ->
                val old = previous
                if (old == null || reading.timestamp - old.timestamp > max(120L, max(reading.intervalMinutes, old.intervalMinutes) * 120L)) path.moveTo(x(reading.timestamp), y(reading))
                else path.lineTo(x(reading.timestamp), y(reading))
                previous = reading
            }
            line.source.id to path
        }
    }
    fun select(timestamp: Long) { selectedTime = timestamp; onDateSelected(Instant.ofEpochSecond(timestamp).atZone(location.zone).toLocalDate()) }
    fun inspect(x: Float) {
        if (timeline.isEmpty()) return
        val fraction = ((x - axisWidth) / (canvasSize.width - axisWidth - rightInset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val target = start + fraction.toDouble() * (end - start)
        Pressure.nearestIndex(timeline, target)?.let { select(timeline[it]) }
    }
    fun move(delta: Int) { if (timeline.isNotEmpty()) select(timeline[(index + delta).coerceIn(0, timeline.lastIndex)]) }
    val legend = sources.filter { it.locationId == location.id }.distinctBy { it.id }.sortedBy { it.priority }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            legend.forEach { source ->
                Text("${sourceLabel(source)} · ${pressureBasis(source)}", color = sourceColor(source, dark), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (timeline.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No pressure readings for this period", color = colors.onSurfaceVariant)
            }
        } else {
            Canvas(Modifier.fillMaxWidth().height((280 * density.fontScale.coerceIn(1f, 1.5f)).dp)
                .onSizeChanged { canvasSize = it }
                .semantics {
                    contentDescription = "Pressure graph from $firstDate to $anchor. ${unit.label}. Campus station pressure uses the left axis, sea-level pressure the right when both are available. Drag to inspect readings."
                    stateDescription = selected.values.filterNotNull().joinToString { "${legend.find { source -> source.id == it.sourceId }?.let(::sourceLabel)} ${pressureText(it.hpa, unit)} ${unit.label}" } +
                        time?.let { ", " + Instant.ofEpochSecond(it).atZone(location.zone).toString() }.orEmpty()
                    customActions = listOf(CustomAccessibilityAction("Previous reading") { move(-1); true }, CustomAccessibilityAction("Next reading") { move(1); true })
                }
                .pointerInput(timeline, start, end, axisWidth, rightInset) { detectTapGestures { inspect(it.x) } }
                .pointerInput(timeline, start, end, axisWidth, rightInset) {
                    detectHorizontalDragGestures(onDragStart = { inspect(it.x) }) { change, _ -> change.consume(); inspect(change.position.x) }
                }
            ) {
                val left = axisWidth; val right = max(left + 1, size.width - rightInset)
                val top = 18.dp.toPx(); val bottom = size.height - 62.dp.toPx()
                val tickCount = if (density.fontScale > 1.4f) 5 else 7
                fun x(timestamp: Long) = left + ((timestamp - start).toDouble() / (end - start)).toFloat() * (right - left)
                fun y(reading: PressureReading): Float {
                    val axis = axes.getValue(reading.kind)
                    return bottom - ((reading.hpa - axis.start) / (axis.endInclusive - axis.start)).toFloat() * (bottom - top)
                }
                fun text(label: String, xx: Float, yy: Float, color: Color = colors.onSurfaceVariant, align: Paint.Align = Paint.Align.CENTER, font: Float = textSize) {
                    paint.color = color.toArgb(); paint.textAlign = align; paint.textSize = font
                    drawContext.canvas.nativeCanvas.drawText(label, xx, yy, paint)
                }
                text((if (primary == PressureKind.STATION) "USU " else "SL ") + unit.label, left - margin, top - margin, align = Paint.Align.RIGHT)
                if (secondary != null) text("SL ${unit.label}", right + margin, top - margin, align = Paint.Align.LEFT)
                repeat(tickCount) { i ->
                    val fraction = i.toDouble() / (tickCount - 1)
                    val yy = bottom - fraction.toFloat() * (bottom - top)
                    drawLine(colors.outlineVariant, Offset(left, yy), Offset(right, yy), 1.dp.toPx())
                    axes[primary]?.let { axis -> text(pressureText(axis.start + fraction * (axis.endInclusive - axis.start), unit), left - margin, yy + textSize / 3,
                        if (primary == PressureKind.STATION) sourceColor(series.first { it.source.isCampus }.source, dark) else colors.onSurfaceVariant, Paint.Align.RIGHT) }
                    secondary?.let { kind -> axes[kind]?.let { axis -> text(pressureText(axis.start + fraction * (axis.endInclusive - axis.start), unit), right + margin, yy + textSize / 3, align = Paint.Align.LEFT) } }
                    val timestamp = start + ((end - start) * fraction).toLong()
                    val xx = x(timestamp)
                    drawLine(colors.outlineVariant.copy(alpha = .6f), Offset(xx, top), Offset(xx, bottom), 1.dp.toPx())
                    val date = Instant.ofEpochSecond(timestamp).atZone(location.zone)
                    val alignment = when (i) { 0 -> Paint.Align.LEFT; tickCount - 1 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                    if (range.days <= 2) {
                        text(date.format(DateTimeFormatter.ofPattern("ha")).lowercase().replace("am", "a").replace("pm", "p"), xx, bottom + 15.dp.toPx(), align = alignment)
                        if (range.days == 2L) text(date.format(DateTimeFormatter.ofPattern("MMM d")), xx, bottom + 28.dp.toPx(), align = alignment)
                    } else text(date.format(DateTimeFormatter.ofPattern("MMM d")), xx, bottom + 18.dp.toPx(), align = alignment)
                }
                series.forEach { line ->
                    drawPath(linePaths.getValue(line.source.id), sourceColor(line.source, dark), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    if (line.readings.size == 1) drawCircle(sourceColor(line.source, dark), 3.dp.toPx(), Offset(x(line.readings[0].timestamp), y(line.readings[0])))
                }
                time?.let { timestamp ->
                    drawLine(colors.onSurfaceVariant.copy(alpha = .6f), Offset(x(timestamp), top), Offset(x(timestamp), bottom + 30.dp.toPx()), 1.dp.toPx())
                    val points = series.mapNotNull { line -> selected[line.source.id]?.let { line.source to it } }.sortedBy { y(it.second) }
                    val boxHeight = max(labelSize * 1.6f, 20.dp.toPx())
                    var previousLabel = top - boxHeight
                    points.forEachIndexed { i, (source, reading) ->
                        val dot = Offset(x(reading.timestamp), y(reading))
                        drawCircle(colors.surface, 6.dp.toPx(), dot)
                        drawCircle(sourceColor(source, dark), 3.5.dp.toPx(), dot)
                        val label = "${pressureText(reading.hpa, unit)} ${unit.label}"
                        paint.textSize = labelSize
                        val width = paint.measureText(label) + 12.dp.toPx()
                        val xx = (if (dot.x < (left + right) / 2) dot.x + margin else dot.x - width - margin).coerceIn(margin, max(margin, size.width - width - margin))
                        val yy = max(y(reading) - boxHeight / 2, previousLabel + boxHeight + 3.dp.toPx()).coerceAtMost(bottom - boxHeight * (points.size - i))
                        previousLabel = yy
                        drawLine(sourceColor(source, dark).copy(alpha = .7f), dot, Offset(xx + width / 2, yy + boxHeight / 2), 1.dp.toPx())
                        drawRoundRect(colors.surface.copy(alpha = .94f), Offset(xx, yy), androidx.compose.ui.geometry.Size(width, boxHeight), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                        text(label, xx + width / 2, yy + (boxHeight + labelSize * .7f) / 2, sourceColor(source, dark), font = labelSize)
                    }
                    val date = Instant.ofEpochSecond(timestamp).atZone(location.zone)
                    val label = date.format(DateTimeFormatter.ofPattern(if (range == ChartRange.DAY) "h:mm:ss a" else "MMM d · h:mm:ss a"))
                    paint.textSize = labelSize
                    val width = paint.measureText(label) + 12.dp.toPx()
                    val xx = (x(timestamp) - width / 2).coerceIn(margin, max(margin, size.width - width - margin))
                    drawRoundRect(colors.surfaceVariant, Offset(xx, bottom + 33.dp.toPx()), androidx.compose.ui.geometry.Size(width, 24.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    text(label, xx + width / 2, bottom + 49.dp.toPx(), colors.onSurface, font = labelSize)
                }
            }
        }
    }
}
