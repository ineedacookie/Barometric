package com.barometric.app

import com.barometric.app.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SymptomWeightsTest {
    private val entry = DailyEntry("2026-10-01", "hyrum-utah", "America/Denver", updatedAt = 1,
        rating = 5, nausea = 1, dizziness = 2, lightSensitivity = 3, soundSensitivity = 4)

    @Test fun `equal weights preserve the arithmetic average at any positive weight`() {
        for (weight in 1..10) {
            val settings = AppSettings(symptomWeights = Symptom.entries.associateWith { weight })
            assertEquals(3.0, entry.symptomAverage(settings)!!, 1e-9)
        }
        assertEquals(3.0, entry.symptomAverage()!!, 1e-9)
    }

    @Test fun `one weight of five contributes five forty-fifths`() {
        val settings = AppSettings(symptomWeights = mapOf(Symptom.HEADACHE to 5))
        assertEquals((5.0 * 5 + (1 + 2 + 3 + 4) * 10) / 45, entry.symptomAverage(settings)!!, 1e-9)
    }

    @Test fun `missing and zero weight symptoms are excluded but entered zeros count`() {
        val settings = AppSettings(symptomWeights = mapOf(Symptom.HEADACHE to 0, Symptom.DIZZINESS to 5))
        val partial = entry.copy(nausea = null, dizziness = 0, lightSensitivity = null)
        assertEquals(40.0 / 15, partial.symptomAverage(settings)!!, 1e-9)
        assertEquals(0.0, partial.copy(soundSensitivity = null).symptomAverage(settings)!!, 0.0)
    }

    @Test fun `no score exists without an entered positive weight`() {
        assertNull(entry.symptomAverage(AppSettings(symptomWeights = Symptom.entries.associateWith { 0 })))
        assertNull(entry.copy(nausea = null, dizziness = null, lightSensitivity = null, soundSensitivity = null)
            .symptomAverage(AppSettings(symptomWeights = mapOf(Symptom.HEADACHE to 0))))
    }

    @Test fun `each of the five symptoms can determine the score independently`() {
        Symptom.entries.forEach { selected ->
            val settings = AppSettings(symptomWeights = Symptom.entries.associateWith { if (it == selected) 10 else 0 })
            assertEquals(selected.value(entry)!!.toDouble(), entry.symptomAverage(settings)!!, 0.0)
        }
    }

    @Test fun `older settings default to equal weights`() {
        val settings = Json.decodeFromString<AppSettings>("{}")
        assertTrue(Symptom.entries.all { settings.symptomWeight(it) == 10 })
    }
}
