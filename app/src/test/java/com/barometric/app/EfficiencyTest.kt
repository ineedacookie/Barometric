package com.barometric.app

import com.barometric.app.data.refreshWindows
import com.barometric.app.domain.Pressure
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class EfficiencyTest {
    private val today = LocalDate.of(2026, 10, 9)

    @Test fun `initial and overlapping refreshes download one combined interval`() {
        assertEquals(listOf(today.minusDays(89)..today), refreshWindows(today, today, today, true))
        assertEquals(listOf(today.minusDays(6)..today), refreshWindows(today, today, today, false))
        assertEquals(listOf(today.minusDays(29)..today), refreshWindows(today, today.minusDays(29), today, false))
        assertEquals(listOf(today.minusDays(89)..today), refreshWindows(today, today.minusDays(89), today, false))
    }

    @Test fun `old date refresh avoids downloading the intervening history`() {
        val past = today.minusDays(180)
        assertEquals(listOf(today.minusDays(6)..today, past.minusDays(1)..past), refreshWindows(today, past.minusDays(1), past, false))
        assertEquals(listOf(today.minusDays(8)..today), refreshWindows(today, today.minusDays(8), today.minusDays(7), false))
    }

    @Test fun `nearest reading handles empty single boundary exact and tied selections`() {
        assertNull(Pressure.nearestIndex(emptyList(), 0.0))
        assertEquals(0, Pressure.nearestIndex(listOf(20L), 100.0))
        val times = listOf(10L, 20L, 30L)
        assertEquals(0, Pressure.nearestIndex(times, -1.0))
        assertEquals(2, Pressure.nearestIndex(times, 100.0))
        assertEquals(1, Pressure.nearestIndex(times, 20.0))
        assertEquals(0, Pressure.nearestIndex(times, 15.0))
        assertEquals(1, Pressure.nearestIndex(times, 15.1))
    }

    @Test fun `large history selection matches a full scan with logarithmic reads`() {
        var reads = 0
        val values = List(100_000) { 1_700_000_000L + it * 180 }
        val times = object : AbstractList<Long>() {
            override val size: Int get() = values.size
            override fun get(index: Int): Long { reads++; return values[index] }
        }
        listOf(-100.0, 0.0, 15.0, 179.5, 8_888_880.0, 18_000_000.0).forEach { offset ->
            val target = values.first() + offset
            val expected = values.indices.minBy { abs(values[it] - target) }
            reads = 0
            assertEquals(expected, Pressure.nearestIndex(times, target))
            assertTrue("Used $reads timestamp reads", reads <= 21)
        }
    }
}
