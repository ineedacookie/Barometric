package com.barometric.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

class HealthRepository(private val context: Context, private val tracker: TrackerRepository) {
    private val mutex = Mutex()
    val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    fun status(): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun granted(): Set<String> = if (status() == HealthConnectClient.SDK_AVAILABLE)
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions() else emptySet()

    suspend fun enabled(steps: Boolean? = null) {
        val permissions = granted()
        tracker.settings.setHealth(
            steps?.let { it && stepsPermission in permissions },
            false,
        )
        if (steps == true) {
            val existing = tracker.settings.flow.first().healthFirstGrantedDate
            if (existing == null) tracker.settings.setHealthDates(LocalDate.now(tracker.currentLocation().zone).toString(), null)
        }
    }

    suspend fun sync(): String? = mutex.withLock {
        val settings = tracker.settings.flow.first()
        if (!settings.syncSteps) return@withLock null
        if (status() != HealthConnectClient.SDK_AVAILABLE) return@withLock "Health Connect is unavailable. You can manage it in Settings."
        val client = HealthConnectClient.getOrCreate(context)
        val permissions = client.permissionController.getGrantedPermissions()
        val stepsEnabled = settings.syncSteps && stepsPermission in permissions
        if (settings.syncSteps && !stepsEnabled) tracker.settings.setHealth(readSteps = false)
        if (!stepsEnabled) return@withLock "Health access is off. Your ratings are still saved."

        val today = LocalDate.now(tracker.currentLocation().zone)
        val firstGranted = settings.healthFirstGrantedDate?.let(LocalDate::parse) ?: today
        val earliest = firstGranted.minusDays(29)
        val start = maxOf(earliest, settings.healthLastSyncDate?.let(LocalDate::parse)?.minusDays(7) ?: earliest)
        var date = start
        var failed = false
        while (date <= today) {
            val location = tracker.locationForDate(date)
            val now = Instant.now()
            val dayStart = date.atStartOfDay(location.zone).toInstant()
            val dayEnd = minOf(date.plusDays(1).atStartOfDay(location.zone).toInstant(), now)
            if (dayStart >= dayEnd) { date = date.plusDays(1); continue }
            var steps: Long? = null
            var gotSteps = false
            val origins = mutableSetOf<String>()
            if (stepsEnabled) {
                try {
                    val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(dayStart, dayEnd)))
                    steps = result[StepsRecord.COUNT_TOTAL]
                    origins += result.dataOrigins.map { it.packageName }
                    gotSteps = true
                } catch (e: Exception) { if (e is CancellationException) throw e; failed = true }
            }
            if (gotSteps) tracker.saveHealth(date, location, steps, null, true, false, origins.toList())
            date = date.plusDays(1)
        }
        // A failed day is retried on the next foreground sync.
        if (!failed) tracker.settings.setHealthDates(firstGranted.toString(), today.toString())
        if (failed) "Some health records were unavailable. Syncing will retry when you open the app." else null
    }
}
