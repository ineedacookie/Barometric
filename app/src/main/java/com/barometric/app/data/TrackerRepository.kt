package com.barometric.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

internal fun refreshWindows(today: LocalDate, from: LocalDate, to: LocalDate, initial: Boolean): List<ClosedRange<LocalDate>> {
    val recentStart = today.minusDays(if (initial) 89 else 6)
    return if (to >= recentStart.minusDays(1)) listOf(minOf(from, recentStart)..today)
    else listOf(recentStart..today, from..to)
}

class TrackerRepository(val database: AppDatabase, val settings: SettingsStore, val api: WeatherApi,
    private val onPressureChanged: suspend () -> Unit = {}) {
    val dao = database.dao()
    private val writes = Mutex()
    private val refreshes = Mutex()

    suspend fun initialize() = writes.withLock {
        settings.applyDefaults()
        database.withTransaction {
            dao.saveLocation(LocationEntity.HYRUM)
            if (dao.periods().isEmpty()) dao.savePeriod(LocationPeriod("1970-01-01", LocationEntity.HYRUM.id))
        }
    }

    suspend fun currentLocation(): LocationEntity {
        val id = settings.flow.first().locationId
        return dao.locations().find { it.id == id } ?: LocationEntity.HYRUM
    }

    suspend fun locationForDate(date: LocalDate): LocationEntity {
        val entry = dao.entry(date.toString())
        val id = entry?.locationId ?: dao.periods().lastOrNull { it.startDate <= date.toString() }?.locationId
        return dao.locations().find { it.id == id } ?: currentLocation()
    }

    suspend fun selectLocation(location: LocationEntity) = writes.withLock {
        database.withTransaction {
            dao.saveLocation(location)
            dao.savePeriod(LocationPeriod(LocalDate.now(location.zone).toString(), location.id))
        }
        settings.setLocation(location.id)
        onPressureChanged()
    }

    suspend fun rate(date: LocalDate, location: LocationEntity, rating: Int?) = writes.withLock {
        require(rating == null || rating in 0..5)
        require(date <= LocalDate.now(location.zone)) { "Future dates cannot be rated" }
        database.withTransaction {
            val existing = dao.entry(date.toString())
            val updated = (existing ?: DailyEntry(date.toString(), location.id, location.timezone, updatedAt = 0))
                .copy(rating = rating, updatedAt = Instant.now().epochSecond)
            if (updated.rating == null && updated.steps == null && updated.sleepMinutes == null && Symptom.entries.all { it.value(updated) == null }) dao.removeEntry(updated.date)
            else dao.saveEntry(updated)
        }
    }

    suspend fun symptom(date: LocalDate, location: LocationEntity, symptom: Symptom, rating: Int) = writes.withLock {
        require(rating in 0..5)
        require(date <= LocalDate.now(location.zone)) { "Future dates cannot be rated" }
        database.withTransaction {
            val old = dao.entry(date.toString()) ?: DailyEntry(date.toString(), location.id, location.timezone, updatedAt = 0)
            val updated = when (symptom) {
                Symptom.HEADACHE -> old.copy(rating = rating)
                Symptom.NAUSEA -> old.copy(nausea = rating)
                Symptom.DIZZINESS -> old.copy(dizziness = rating)
                Symptom.LIGHT_SENSITIVITY -> old.copy(lightSensitivity = rating)
                Symptom.SOUND_SENSITIVITY -> old.copy(soundSensitivity = rating)
            }
            dao.saveEntry(updated.copy(updatedAt = Instant.now().epochSecond))
        }
    }

    suspend fun refreshForView(location: LocationEntity, from: LocalDate, to: LocalDate, force: Boolean = false) {
        val today = LocalDate.now(location.zone)
        val initial = dao.sources(location.id).filter { it.isActive }.none { it.lastSuccess != null }
        for (window in refreshWindows(today, from, to, initial)) {
            refresh(location, window.start, window.endInclusive, force, fullResolution = true)
        }
    }

    suspend fun refresh(location: LocationEntity, from: LocalDate, to: LocalDate, force: Boolean = false, fullResolution: Boolean = false) = refreshes.withLock {
        val start = from.atStartOfDay(location.zone).toInstant()
        val end = to.plusDays(1).atStartOfDay(location.zone).toInstant()
        val storedSources = dao.sources(location.id)
        val existing = storedSources.filter { it.isActive }
        val sourceChanged = storedSources.any { !it.isActive } && existing.none { it.isCampus } && api.knownUsu(location) != null
        val oldReadings = dao.readings(location.id, start.epochSecond, end.epochSecond)
        val recent = existing.isNotEmpty() && existing.all { it.lastAttempt != null && Instant.now().epochSecond - it.lastAttempt < 3600 }
        val daysCovered = oldReadings.filter { reading -> existing.any { it.id == reading.sourceId && it.pressureKind == reading.kind } }.map { it.instant.atZone(location.zone).toLocalDate() }.toSet()
        val requestedDays = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
        val usuIds = existing.filter { it.provider == "USU" }.map { it.id }.toSet()
        val fineEnough = !fullResolution || oldReadings.filter { it.sourceId in usuIds }.all { it.intervalMinutes <= 10 }
        if (!sourceChanged && !force && recent && fineEnough && daysCovered.containsAll(requestedDays)) return@withLock

        val discovered = if (sourceChanged || existing.isEmpty() || force || existing.any { it.error != null }) discover(location, existing) else existing
        if (discovered != existing) writes.withLock { dao.saveSources(discovered) }
        for (source in discovered) {
            val now = Instant.now().epochSecond
            try {
                val data = api.fetch(source, location, start, end, fullResolution)
                writes.withLock {
                    database.withTransaction {
                        dao.saveReadings(data)
                        dao.saveSources(listOf(source.copy(lastAttempt = now,
                            lastSuccess = if (data.isNotEmpty()) now else source.lastSuccess,
                            error = if (data.none { it.kind == source.pressureKind })
                                if (source.isCampus) "Detailed campus history covers the latest seven days. Saved history is kept." else "No sea-level readings for these dates"
                                else null)))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                writes.withLock { dao.saveSources(listOf(source.copy(lastAttempt = now, error = "Temporarily unavailable. Saved readings are kept."))) }
            }
        }
        onPressureChanged()
    }

    private suspend fun discover(location: LocationEntity, existing: List<SourceEntity>): List<SourceEntity> {
        suspend fun attempt(block: suspend () -> SourceEntity?): SourceEntity? = try { block() } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val usu = api.discoverUsu(location)
        val noaa = attempt { api.discoverNoaa(location) } ?: existing.find { it.provider == "NOAA" }
        return listOfNotNull(usu, noaa, api.modeled(location)).map { found ->
            existing.find { it.id == found.id }?.let { found.copy(lastAttempt = it.lastAttempt, lastSuccess = it.lastSuccess, error = it.error) } ?: found
        }
    }

    suspend fun clearHealth() = writes.withLock {
        settings.setHealth(false, false)
        dao.clearHealth()
    }

    suspend fun saveHealth(date: LocalDate, location: LocationEntity, steps: Long?, sleepMinutes: Long?,
        replaceSteps: Boolean, replaceSleep: Boolean, origins: List<String>) = writes.withLock {
        database.withTransaction {
            val old = dao.entry(date.toString()) ?: DailyEntry(date.toString(), location.id, location.timezone, updatedAt = Instant.now().epochSecond)
            dao.saveEntry(old.copy(steps = if (replaceSteps) steps else old.steps,
                sleepMinutes = if (replaceSleep) sleepMinutes else old.sleepMinutes,
                healthOrigins = origins.distinct(), healthSyncedAt = Instant.now().epochSecond))
        }
    }

    suspend fun deleteAll() = refreshes.withLock {
        writes.withLock {
            database.withTransaction {
                dao.clearEntries(); dao.clearReadings(); dao.clearSources(); dao.clearPeriods(); dao.clearLocations()
                dao.saveLocation(LocationEntity.HYRUM)
                dao.savePeriod(LocationPeriod("1970-01-01", LocationEntity.HYRUM.id))
            }
            settings.reset()
            onPressureChanged()
        }
    }
}
