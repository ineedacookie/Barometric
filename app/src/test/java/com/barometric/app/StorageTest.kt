package com.barometric.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.barometric.app.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StorageTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var tracker: TrackerRepository
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        tracker = TrackerRepository(database, SettingsStore(context), WeatherApi())
        runBlocking { tracker.settings.reset() }
    }
    @After fun close() { database.close() }

    @Test fun `zero persists and clearing preserves independent health totals`() = runTest {
        tracker.initialize()
        val date = LocalDate.now(LocationEntity.HYRUM.zone).minusDays(1)
        tracker.rate(date, LocationEntity.HYRUM, 0)
        assertEquals(0, database.dao().entry(date.toString())!!.rating)
        tracker.saveHealth(date, LocationEntity.HYRUM, 3000, null, true, false, listOf("fitbit"))
        tracker.rate(date, LocationEntity.HYRUM, null)
        val entry = database.dao().entry(date.toString())!!
        assertNull(entry.rating)
        assertEquals(3000L, entry.steps)
    }

    @Test fun `past ratings retain their location when edited after location change`() = runTest {
        tracker.initialize()
        val date = LocalDate.now(LocationEntity.HYRUM.zone).minusDays(3)
        tracker.rate(date, LocationEntity.HYRUM, 2)
        val other = LocationEntity("new", "Other", "Town", 40.0, -100.0, "America/Chicago")
        tracker.selectLocation(other)
        tracker.rate(date, other, 3)
        val stored = database.dao().entry(date.toString())!!
        assertEquals(LocationEntity.HYRUM.id, stored.locationId)
        assertEquals("America/Denver", stored.timezone)
        assertEquals(3, stored.rating)
    }

    @Test fun `duplicate downloads upsert while preserving pressure type`() = runTest {
        tracker.initialize()
        val reading = PressureReading(LocationEntity.HYRUM.id, "usu-17", 100, 1000.0, intervalMinutes = 10)
        database.dao().saveReadings(listOf(reading, reading.copy(hpa = 1001.0)))
        database.dao().saveReadings(listOf(reading.copy(kind = PressureKind.STATION, hpa = 870.0)))
        assertEquals(2, database.dao().readings().size)
        assertEquals(1001.0, database.dao().readings().first { it.kind == PressureKind.SEA_LEVEL }.hpa, 0.0)
    }

    @Test fun `future dates are rejected`() = runTest {
        tracker.initialize()
        try {
            tracker.rate(LocalDate.now(LocationEntity.HYRUM.zone).plusDays(1), LocationEntity.HYRUM, 1)
            fail("Future rating was accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `rating survives closing and reopening the database`() = runTest {
        val name = "barometric-restart-test.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val date = LocalDate.now(LocationEntity.HYRUM.zone).minusDays(1).toString()
        try {
            disk.dao().saveLocation(LocationEntity.HYRUM)
            disk.dao().saveEntry(DailyEntry(date, LocationEntity.HYRUM.id, "America/Denver", rating = 0, updatedAt = 1))
            disk.close()
            disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(0, disk.dao().entry(date)!!.rating)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun `JSON export includes metadata readings settings and null ratings`() = runTest {
        tracker.initialize()
        tracker.settings.setSymptomWeight(Symptom.HEADACHE, 5)
        val date = LocalDate.now(LocationEntity.HYRUM.zone).minusDays(1)
        tracker.saveHealth(date, LocationEntity.HYRUM, 25, 450, true, true, listOf("fitbit"))
        database.dao().saveSources(listOf(SourceEntity(LocationEntity.HYRUM.id, "usu-17", "USU", "River Heights", "17", 41.7254, -111.8064, 10.0, false, 0)))
        database.dao().saveReadings(listOf(PressureReading(LocationEntity.HYRUM.id, "usu-17", 100, 1000.0, intervalMinutes = 10)))
        val snapshot = Exporter(context, tracker).snapshot()
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<ExportBundle>(json.encodeToString(snapshot))
        assertEquals(4, restored.schemaVersion)
        assertEquals(5, restored.settings.symptomWeight(Symptom.HEADACHE))
        assertEquals(10, restored.settings.symptomWeight(Symptom.NAUSEA))
        assertEquals("hPa", restored.pressureUnit)
        assertEquals(1, restored.readings.size)
        assertNull(restored.entries.single().rating)
        assertEquals(450L, restored.entries.single().sleepMinutes)
        assertTrue(restored.locations.any { it.id == LocationEntity.HYRUM.id })
        assertTrue(restored.locationPeriods.isNotEmpty())
        assertEquals(0.0, restored.dailyPressure.single().differenceHpa, 0.0)
    }

    @Test fun `weights persist independently and reset to equal defaults`() = runTest {
        tracker.settings.setSymptomWeight(Symptom.HEADACHE, 5)
        tracker.settings.setSymptomWeight(Symptom.SOUND_SENSITIVITY, 0)
        tracker.settings.applyDefaults()
        val settings = SettingsStore(context).flow.first()
        assertEquals(5, settings.symptomWeight(Symptom.HEADACHE))
        assertEquals(0, settings.symptomWeight(Symptom.SOUND_SENSITIVITY))
        assertEquals(10, settings.symptomWeight(Symptom.NAUSEA))
        tracker.settings.reset()
        assertTrue(Symptom.entries.all { tracker.settings.flow.first().symptomWeight(it) == 10 })
    }

    @Test fun `weights outside zero through ten are rejected`() = runTest {
        for (weight in listOf(-1, 11)) {
            try {
                tracker.settings.setSymptomWeight(Symptom.HEADACHE, weight)
                fail("Invalid weight was accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(10, tracker.settings.flow.first().symptomWeight(Symptom.HEADACHE))
    }
}
