package com.barometric.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class DatabaseConverters {
    @TypeConverter fun pressureKind(value: String): PressureKind = PressureKind.valueOf(value)
    @TypeConverter fun pressureKind(value: PressureKind): String = value.name
    @TypeConverter fun origins(value: String): List<String> = Json.decodeFromString(value)
    @TypeConverter fun origins(value: List<String>): String = Json.encodeToString(value)
}

@Dao
interface AppDao {
    @Query("SELECT * FROM locations") fun observeLocations(): Flow<List<LocationEntity>>
    @Query("SELECT * FROM location_periods ORDER BY startDate") fun observePeriods(): Flow<List<LocationPeriod>>
    @Query("SELECT * FROM entries ORDER BY date") fun observeEntries(): Flow<List<DailyEntry>>
    @Query("SELECT * FROM sources ORDER BY priority") fun observeSources(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM readings WHERE timestamp >= :since ORDER BY timestamp")
    fun observeReadings(since: Long): Flow<List<PressureReading>>
    @Query("SELECT * FROM locations") suspend fun locations(): List<LocationEntity>
    @Query("SELECT * FROM location_periods ORDER BY startDate") suspend fun periods(): List<LocationPeriod>
    @Query("SELECT * FROM sources ORDER BY priority") suspend fun sources(): List<SourceEntity>
    @Query("SELECT * FROM readings ORDER BY timestamp") suspend fun readings(): List<PressureReading>
    @Query("SELECT * FROM readings WHERE locationId = :locationId AND timestamp >= :start AND timestamp < :end ORDER BY timestamp")
    suspend fun readings(locationId: String, start: Long, end: Long): List<PressureReading>
    @Query("SELECT * FROM entries ORDER BY date") suspend fun entries(): List<DailyEntry>
    @Query("SELECT * FROM entries WHERE date = :date") suspend fun entry(date: String): DailyEntry?
    @Query("SELECT * FROM sources WHERE locationId = :locationId ORDER BY priority")
    suspend fun sources(locationId: String): List<SourceEntity>
    @Upsert suspend fun saveLocation(location: LocationEntity)
    @Upsert suspend fun savePeriod(period: LocationPeriod)
    @Upsert suspend fun saveSources(sources: List<SourceEntity>)
    @Upsert suspend fun saveReadings(readings: List<PressureReading>)
    @Upsert suspend fun saveEntry(entry: DailyEntry)
    @Query("DELETE FROM entries WHERE date = :date") suspend fun removeEntry(date: String)
    @Query("UPDATE entries SET steps = NULL, sleepMinutes = NULL, healthOrigins = '[]', healthSyncedAt = NULL")
    suspend fun clearHealth()
    @Query("DELETE FROM readings") suspend fun clearReadings()
    @Query("DELETE FROM sources") suspend fun clearSources()
    @Query("DELETE FROM entries") suspend fun clearEntries()
    @Query("DELETE FROM location_periods") suspend fun clearPeriods()
    @Query("DELETE FROM locations") suspend fun clearLocations()
}

@Database(
    entities = [LocationEntity::class, LocationPeriod::class, SourceEntity::class, PressureReading::class, DailyEntry::class],
    version = 4, exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN soundSensitivity INTEGER")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // An explicit No becomes zero. Yes does not establish severity.
                db.execSQL("UPDATE entries SET nausea = CASE WHEN nausea = 0 THEN 0 ELSE NULL END, dizziness = CASE WHEN dizziness = 0 THEN 0 ELSE NULL END, lightSensitivity = CASE WHEN lightSensitivity = 0 THEN 0 ELSE NULL END")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN nausea INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN dizziness INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN lightSensitivity INTEGER")
            }
        }
    }
}
