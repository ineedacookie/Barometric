package com.barometric.app

import com.barometric.app.domain.Insights
import org.junit.Assert.*
import org.junit.Test

class InsightsTest {
    @Test fun `fewer than thirty pairs never produce a correlation`() {
        assertNull(Insights.spearman((0..28).map { it.toDouble() to it.toDouble() }))
    }
    @Test fun `constant ratings are not evidence of no association`() {
        assertNull(Insights.spearman((0..29).map { 0.0 to it.toDouble() }))
    }
    @Test fun `tied ordinal ratings use average ranks`() {
        val pairs = (0..35).map { (it / 6).toDouble() to (it / 6).toDouble() }
        assertEquals(1.0, Insights.spearman(pairs)!!, 1e-12)
        assertEquals(-1.0, Insights.spearman(pairs.map { it.first to -it.second })!!, 1e-12)
    }
}
