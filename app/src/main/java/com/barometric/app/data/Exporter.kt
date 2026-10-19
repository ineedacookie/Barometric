package com.barometric.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.barometric.app.domain.Pressure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.time.Instant

@Serializable
data class ExportDailyPressure(
    val locationId: String, val date: String, val timezone: String, val sourceId: String,
    val highHpa: Double, val lowHpa: Double, val differenceHpa: Double,
    val sampleCount: Int, val coverage: Double, val complete: Boolean,
    val pressureKind: PressureKind = PressureKind.SEA_LEVEL,
)

@Serializable
data class ExportBundle(
    val schemaVersion: Int = 4,
    val exportedAt: String,
    val timestampUnit: String = "Unix seconds in UTC",
    val pressureUnit: String = "hPa",
    val sleepUnit: String = "minutes; previous noon to date noon",
    val symptomEncoding: String = "rating: headache; nausea, dizziness, lightSensitivity, soundSensitivity: integer severity 0–5; null: unlogged. Daily scores use settings.symptomWeights (0–10, default 10), normalized over entered symptoms only. Zero weights are excluded; zero total weight gives no score. Current weights apply to all days.",
    val settings: AppSettings,
    val locations: List<LocationEntity>,
    val locationPeriods: List<LocationPeriod>,
    val sources: List<SourceEntity>,
    val readings: List<PressureReading>,
    val entries: List<DailyEntry>,
    val dailyPressure: List<ExportDailyPressure>,
)

class Exporter(private val context: Context, private val tracker: TrackerRepository) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun snapshot(): ExportBundle {
        val settings = tracker.settings.flow.first()
        return tracker.database.withTransaction {
            val locations = tracker.dao.locations()
            val sources = tracker.dao.sources()
            val readings = tracker.dao.readings()
            val daily = locations.flatMap { location ->
                Pressure.summarize(location, sources, readings).map { day ->
                    ExportDailyPressure(location.id, day.date.toString(), location.timezone, day.source.id,
                        day.high, day.low, day.difference, day.readings.size, day.coverage, day.complete, day.source.pressureKind)
                }
            }
            ExportBundle(exportedAt = Instant.now().toString(), settings = settings,
                locations = locations, locationPeriods = tracker.dao.periods(),
                sources = sources, readings = readings, entries = tracker.dao.entries(), dailyPressure = daily)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val data = snapshot()
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open selected file")
        stream.buffered().use { json.encodeToStream(data, it) }
    }
}
