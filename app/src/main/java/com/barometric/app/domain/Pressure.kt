package com.barometric.app.domain

import com.barometric.app.data.*
import java.time.*
import kotlin.math.*

object Pressure {
    const val HPA_PER_INHG = 33.8638866667
    fun convert(hpa: Double, unit: PressureUnit): Double = when (unit) {
        PressureUnit.KPA -> hpa / 10.0
        PressureUnit.INHG -> hpa / HPA_PER_INHG
        PressureUnit.HPA -> hpa
    }

    fun valid(value: Double): Boolean = value.isFinite() && value in 300.0..1100.0

    // Timestamps are sorted; ties select the earlier reading.
    fun nearestIndex(timestamps: List<Long>, target: Double): Int? {
        if (timestamps.isEmpty()) return null
        var low = 0
        var high = timestamps.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (timestamps[middle] < target) low = middle + 1 else high = middle
        }
        return when {
            low == 0 -> 0
            low == timestamps.size -> timestamps.lastIndex
            target - timestamps[low - 1] <= timestamps[low] - target -> low - 1
            else -> low
        }
    }

    fun summarize(
        location: LocationEntity, sources: List<SourceEntity>, readings: List<PressureReading>,
        now: Instant = Instant.now(),
    ): List<DaySummary> {
        val zone = location.zone
        val today = now.atZone(zone).toLocalDate()
        val sourcesById = sources.filter { it.locationId == location.id }.associateBy { it.id }
        return readings.asSequence().filter {
            it.locationId == location.id && it.kind == sourcesById[it.sourceId]?.pressureKind && valid(it.hpa) && it.instant <= now
        }.groupBy { it.instant.atZone(zone).toLocalDate() to it.sourceId }.mapNotNull { (key, values) ->
            val source = sourcesById[key.second] ?: return@mapNotNull null
            val date = key.first
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val hours = Duration.between(start, end).toHours().toDouble()
            val occupiedHours = values.map { Duration.between(start, it.instant).toHours() }.distinct().size
            DaySummary(date, source, values.sortedBy { it.timestamp }, values.maxOf { it.hpa }, values.minOf { it.hpa },
                values.map { it.hpa }.average(), (occupiedHours / hours).coerceIn(0.0, 1.0), date == today)
        }.sortedWith(compareBy<DaySummary> { it.date }.thenBy { it.source.priority })
    }

    fun preferred(summaries: List<DaySummary>, sourceId: String? = null): DaySummary? =
        summaries.filter { sourceId == null || it.source.id == sourceId }
            .sortedWith(compareBy<DaySummary> { !it.complete && !it.isToday }.thenBy { it.source.priority })
            .firstOrNull()

    // Station and sea-level pressure use separate axes with the same span, so an
    // equal pressure change has the same visual height without changing raw values.
    fun chartAxes(readings: List<PressureReading>): Map<PressureKind, ClosedFloatingPointRange<Double>> {
        val bounds = readings.groupBy { it.kind }.mapValues { (_, values) -> values.minOf { it.hpa }..values.maxOf { it.hpa } }
        val span = bounds.values.maxOfOrNull { it.endInclusive - it.start } ?: 0.0
        val padding = max(span * .15, .8)
        return bounds.mapValues { (_, range) ->
            val center = (range.start + range.endInclusive) / 2
            (center - span / 2 - padding)..(center + span / 2 + padding)
        }
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt((1 - a).coerceAtLeast(0.0)))
    }
}
