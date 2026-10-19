package com.barometric.app

import com.barometric.app.data.*
import com.barometric.app.domain.Pressure
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PressureTest {
    private val location = LocationEntity.HYRUM
    private fun source(id: String, priority: Int = 0) = SourceEntity(location.id, id, "USU", id, "17", 41.7, -111.8, 10.0, false, priority)
    private fun readings(date: LocalDate, sourceId: String, values: List<Double>): List<PressureReading> {
        val start = date.atStartOfDay(location.zone).toInstant()
        return values.mapIndexed { hour, value -> PressureReading(location.id, sourceId, start.plusSeconds(hour * 3600L).epochSecond, value, intervalMinutes = 60) }
    }

    @Test fun `high minus low is computed within one source`() {
        val date = LocalDate.of(2026, 10, 1)
        val sources = listOf(source("near"), source("far", 1))
        val rows = readings(date, "near", listOf(1000.0, 1005.0, 1002.0)) + readings(date, "far", listOf(1010.0, 1011.0))
        val summaries = Pressure.summarize(location, sources, rows, Instant.parse("2026-10-03T00:00:00Z"))
        assertEquals(5.0, summaries.first { it.source.id == "near" }.difference, 0.0001)
        assertEquals(1.0, summaries.first { it.source.id == "far" }.difference, 0.0001)
        assertEquals("near", Pressure.preferred(summaries)!!.source.id)
    }

    @Test fun `pressure conversion does not convert absolute pressure into a range`() {
        assertEquals(1.0, Pressure.convert(Pressure.HPA_PER_INHG, PressureUnit.INHG), 1e-9)
        assertEquals(1000.0, Pressure.convert(1000.0, PressureUnit.HPA), 1e-9)
        assertFalse(Pressure.valid(-9999.0))
        assertFalse(Pressure.valid(Double.NaN))
    }

    @Test fun `surface and altimeter pressure cannot enter sea level summaries`() {
        val date = LocalDate.of(2026, 10, 1)
        val row = readings(date, "near", listOf(1000.0)).first()
        val rows = listOf(row, row.copy(kind = PressureKind.STATION, hpa = 850.0), row.copy(kind = PressureKind.ALTIMETER, hpa = 1040.0))
        assertEquals(0.0, Pressure.summarize(location, listOf(source("near")), rows, Instant.parse("2026-10-03T00:00:00Z")).single().difference, 0.0)
    }

    @Test fun `coverage uses the real 23 or 25 hour DST day`() {
        listOf(LocalDate.of(2026, 3, 8) to 23, LocalDate.of(2026, 11, 1) to 25).forEach { (date, hours) ->
            val rows = readings(date, "near", List(hours) { 1000.0 + it })
            val day = Pressure.summarize(location, listOf(source("near")), rows, Instant.parse("2026-12-01T00:00:00Z")).single()
            assertEquals(1.0, day.coverage, 0.0)
            assertTrue(day.complete)
        }
    }

    @Test fun `duplicate samples in one hour cannot inflate coverage`() {
        val date = LocalDate.of(2026, 10, 1)
        val row = readings(date, "near", listOf(1000.0)).single()
        val rows = (0..50).map { row.copy(timestamp = row.timestamp + it) }
        val summary = Pressure.summarize(location, listOf(source("near")), rows, Instant.parse("2026-10-03T00:00:00Z")).single()
        assertEquals(1.0 / 24, summary.coverage, 1e-9)
        assertFalse(summary.complete)
    }

    @Test fun `today and sparse days are incomplete and complete fallback is preferred`() {
        val date = LocalDate.of(2026, 10, 1)
        val rows = readings(date, "near", listOf(1000.0)) + readings(date, "model", List(24) { 1000.0 })
        val sources = listOf(source("near"), source("model", 2))
        val completed = Pressure.summarize(location, sources, rows, Instant.parse("2026-10-03T00:00:00Z"))
        assertEquals("model", Pressure.preferred(completed)!!.source.id)
        val today = Pressure.summarize(location, sources, rows, date.atTime(23, 59).atZone(location.zone).toInstant())
        assertTrue(today.all { !it.complete && it.isToday })
    }

    @Test fun `midnight boundary assigns measurements to location day`() {
        val time = Instant.parse("2026-10-02T05:59:00Z") // Oct 1, 11:59 PM MDT
        val row = PressureReading(location.id, "near", time.epochSecond, 1000.0, intervalMinutes = 10)
        assertEquals(LocalDate.of(2026, 10, 1), Pressure.summarize(location, listOf(source("near")), listOf(row), time.plusSeconds(120)).single().date)
    }
}
