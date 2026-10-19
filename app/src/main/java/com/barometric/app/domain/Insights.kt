package com.barometric.app.domain

import com.barometric.app.data.*
import java.time.LocalDate
import kotlin.math.sqrt

data class Insight(val location: String, val source: String, val metric: String, val count: Int, val correlation: Double?, val start: String?, val end: String?)

object Insights {
    const val MIN_PAIRS = 30

    fun spearman(pairs: List<Pair<Double, Double>>): Double? {
        if (pairs.size < MIN_PAIRS) return null
        fun ranks(values: List<Double>): List<Double> {
            val ranks = DoubleArray(values.size)
            val sorted = values.indices.sortedBy { values[it] }
            var i = 0
            while (i < sorted.size) {
                var j = i + 1
                while (j < sorted.size && values[sorted[i]] == values[sorted[j]]) j++
                val rank = (i + j - 1) / 2.0 + 1
                for (k in i until j) ranks[sorted[k]] = rank
                i = j
            }
            return ranks.toList()
        }
        val xs = ranks(pairs.map { it.first }); val ys = ranks(pairs.map { it.second })
        val mx = xs.average(); val my = ys.average()
        var covariance = 0.0; var xx = 0.0; var yy = 0.0
        xs.indices.forEach { i ->
            val x = xs[i] - mx; val y = ys[i] - my
            covariance += x * y; xx += x * x; yy += y * y
        }
        return if (xx == 0.0 || yy == 0.0) null else (covariance / sqrt(xx * yy)).coerceIn(-1.0, 1.0)
    }

    fun calculate(locations: List<LocationEntity>, entries: List<DailyEntry>, summaries: List<DaySummary>): List<Insight> {
        val result = mutableListOf<Insight>()
        locations.forEach { location ->
            val logged = entries.filter { it.locationId == location.id && it.rating != null }
            summaries.filter { it.source.locationId == location.id && it.complete }.groupBy { it.source.id }.values.forEach { sourceDays ->
                val byDate = sourceDays.associateBy { it.date }
                val source = sourceDays.first().source
                listOf("Same-day pressure range" to 0L, "Previous-day pressure range" to 1L).forEach { (metric, lag) ->
                    val points = logged.mapNotNull { entry ->
                        val date = LocalDate.parse(entry.date)
                        // Require a complete pressure day for the rating and the lagged value.
                        if (byDate[date] == null) return@mapNotNull null
                        byDate[date.minusDays(lag)]?.let { Triple(entry.date, entry.rating!!.toDouble(), it.difference) }
                    }
                    result += insight(location, source.name, metric, points)
                }
            }
            listOf("Daily steps" to true, "Sleep before this day" to false).forEach { (metric, isSteps) ->
                val points = logged.mapNotNull { e ->
                    val value = if (isSteps) e.steps else e.sleepMinutes
                    if (LocalDate.parse(e.date) >= LocalDate.now(location.zone)) null
                    else value?.let { Triple(e.date, e.rating!!.toDouble(), it.toDouble()) }
                }
                if (points.isNotEmpty()) result += insight(location, "Health Connect", metric, points)
            }
        }
        return result
    }

    private fun insight(location: LocationEntity, source: String, metric: String, points: List<Triple<String, Double, Double>>): Insight =
        Insight(location.label, source, metric, points.size, spearman(points.map { it.second to it.third }),
            points.minOfOrNull { it.first }, points.maxOfOrNull { it.first })
}
