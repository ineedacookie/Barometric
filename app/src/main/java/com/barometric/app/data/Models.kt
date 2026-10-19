package com.barometric.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
) {
    val zone: ZoneId get() = ZoneId.of(timezone)
    val label: String get() = listOf(name, region).filter { it.isNotBlank() }.joinToString(", ")
    companion object {
        val HYRUM = LocationEntity("hyrum-utah", "Hyrum", "Utah", 41.6341, -111.8522, "America/Denver")
    }
}

@Serializable
@Entity(tableName = "location_periods")
data class LocationPeriod(@PrimaryKey val startDate: String, val locationId: String)

@Serializable
@Entity(tableName = "sources", primaryKeys = ["locationId", "id"])
data class SourceEntity(
    val locationId: String,
    val id: String,
    val provider: String,
    val name: String,
    val stationId: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceKm: Double,
    val modeled: Boolean,
    val priority: Int,
    val lastAttempt: Long? = null,
    val lastSuccess: Long? = null,
    val error: String? = null,
) {
    val isCampus: Boolean get() = id == "usu-campus"
    val isActive: Boolean get() = provider != "USU" || isCampus
    val pressureKind: PressureKind get() = if (isCampus) PressureKind.STATION else PressureKind.SEA_LEVEL
}

@Serializable
enum class PressureKind { SEA_LEVEL, STATION, ALTIMETER }

@Serializable
@Entity(tableName = "readings", primaryKeys = ["locationId", "sourceId", "timestamp", "kind"],
    indices = [androidx.room.Index("timestamp"), androidx.room.Index(value = ["locationId", "timestamp"])])
data class PressureReading(
    val locationId: String,
    val sourceId: String,
    val timestamp: Long,
    val hpa: Double,
    val kind: PressureKind = PressureKind.SEA_LEVEL,
    val intervalMinutes: Int,
) {
    val instant: Instant get() = Instant.ofEpochSecond(timestamp)
}

@Serializable
@Entity(tableName = "entries")
data class DailyEntry(
    @PrimaryKey val date: String,
    val locationId: String,
    val timezone: String,
    val rating: Int? = null,
    val steps: Long? = null,
    val sleepMinutes: Long? = null,
    val healthOrigins: List<String> = emptyList(),
    val updatedAt: Long,
    val healthSyncedAt: Long? = null,
    val nausea: Int? = null,
    val dizziness: Int? = null,
    val lightSensitivity: Int? = null,
    val soundSensitivity: Int? = null,
) {
    fun symptomAverage(settings: AppSettings = AppSettings()): Double? {
        val entered = Symptom.entries.mapNotNull { symptom ->
            symptom.value(this)?.let { it to settings.symptomWeight(symptom) }
        }
        val totalWeight = entered.sumOf { it.second }
        return if (totalWeight == 0) null else entered.sumOf { (value, weight) -> value.toDouble() * weight } / totalWeight
    }
    val symptomsComplete: Boolean get() = Symptom.entries.all { it.value(this) != null }
}

@Serializable
enum class Symptom(val label: String) {
    HEADACHE("Headache"), NAUSEA("Nausea"), DIZZINESS("Dizziness"), LIGHT_SENSITIVITY("Light sensitivity"), SOUND_SENSITIVITY("Sound");

    fun value(entry: DailyEntry?): Int? = when (this) {
        HEADACHE -> entry?.rating
        NAUSEA -> entry?.nausea
        DIZZINESS -> entry?.dizziness
        LIGHT_SENSITIVITY -> entry?.lightSensitivity
        SOUND_SENSITIVITY -> entry?.soundSensitivity
    }
}

@Serializable
enum class PressureUnit(val label: String) { KPA("kPa"), INHG("inHg"), HPA("hPa") }

@Serializable
data class AppSettings(
    val locationId: String = LocationEntity.HYRUM.id,
    val unit: PressureUnit = PressureUnit.KPA,
    val syncSteps: Boolean = false,
    val syncSleep: Boolean = false,
    val healthFirstGrantedDate: String? = null,
    val healthLastSyncDate: String? = null,
    val symptomWeights: Map<Symptom, Int> = Symptom.entries.associateWith { 10 },
) {
    fun symptomWeight(symptom: Symptom): Int = (symptomWeights[symptom] ?: 10).coerceIn(0, 10)
}

data class DaySummary(
    val date: LocalDate,
    val source: SourceEntity,
    val readings: List<PressureReading>,
    val high: Double,
    val low: Double,
    val mean: Double,
    val coverage: Double,
    val isToday: Boolean,
) {
    val difference: Double get() = high - low
    val complete: Boolean get() = !isToday && coverage >= .75
}
