package com.barometric.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.barometric.app.data.*
import com.barometric.app.domain.Pressure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UpdateTest {
    @Test fun `version one update preserves all existing data`() = verifyUpdate(1)
    @Test fun `version two update preserves data and converts only established absence`() = verifyUpdate(2)

    private fun verifyUpdate(version: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "update-from-$version.db"
        context.deleteDatabase(name)
        val schemaFile = java.io.File("schemas/com.barometric.app.data.AppDatabase/$version.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            schema["entities"]!!.jsonArray.forEach { item ->
                val entity = item.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                fun sql(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                old.execSQL(sql(entity["createSql"]!!))
                entity["indices"]?.jsonArray.orEmpty().forEach { old.execSQL(sql(it.jsonObject["createSql"]!!)) }
            }
            old.execSQL("INSERT INTO locations VALUES ('hyrum-utah', 'Hyrum', 'Utah', 41.6341, -111.8522, 'America/Denver')")
            old.execSQL("INSERT INTO location_periods VALUES ('2026-10-01', 'hyrum-utah')")
            old.execSQL("INSERT INTO sources VALUES ('hyrum-utah', 'usu-campus', 'USU', 'USU Campus', '1279257', 41.7423, -111.81223, 12.5, 0, 0, 100, 100, NULL)")
            old.execSQL("INSERT INTO readings VALUES ('hyrum-utah', 'usu-campus', 100, 849.0, 'STATION', 3)")
            old.execSQL("INSERT INTO entries(date, locationId, timezone, rating, steps, sleepMinutes, healthOrigins, updatedAt, healthSyncedAt) VALUES ('2026-10-01', 'hyrum-utah', 'America/Denver', 4, 3210, 420, '[\"fitbit\"]', 100, 100)")
            if (version == 2) old.execSQL("UPDATE entries SET nausea = 0, dizziness = 1, lightSensitivity = NULL")
            old.version = version
        }
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).build()
        try {
            val dao = database.dao()
            val entry = dao.entry("2026-10-01")!!
            assertEquals(4, entry.rating)
            assertEquals(3210L, entry.steps)
            assertEquals(420L, entry.sleepMinutes)
            assertEquals(listOf("fitbit"), entry.healthOrigins)
            assertEquals("America/Denver", entry.timezone)
            assertEquals(100L, entry.updatedAt)
            assertEquals(100L, entry.healthSyncedAt)
            assertEquals(if (version == 2) 0 else null, entry.nausea)
            assertNull(entry.dizziness)
            assertNull(entry.lightSensitivity)
            assertEquals(1, dao.locations().size)
            assertEquals(1, dao.periods().size)
            assertEquals(1, dao.sources().size)
            assertEquals(849.0, dao.readings().single().hpa, 0.0)
            dao.saveEntry(entry.copy(dizziness = 3))
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(3, database.dao().entry(entry.date)!!.dizziness)
            assertEquals(4, database.dao().entry(entry.date)!!.rating)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun `calendar average excludes missing ratings and includes zero`() {
        val empty = DailyEntry("2026-10-01", "hyrum-utah", "America/Denver", updatedAt = 1)
        assertNull(empty.symptomAverage())
        assertFalse(empty.symptomsComplete)
        assertEquals(2.0, empty.copy(rating = 4, nausea = 0).symptomAverage()!!, 0.0)
        val completed = empty.copy(rating = 0, nausea = 2, dizziness = 4, lightSensitivity = 2, soundSensitivity = 2)
        assertEquals(2.0, completed.symptomAverage()!!, 0.0)
        assertTrue(completed.symptomsComplete)
    }

    @Test fun `all pressure displays convert to kPa without rewriting stored data`() {
        assertEquals(84.9, Pressure.convert(849.0, PressureUnit.KPA), 1e-9)
        assertEquals(.15, Pressure.convert(1.5, PressureUnit.KPA), 1e-9)
        assertEquals(PressureUnit.KPA, AppSettings().unit)
    }
}
