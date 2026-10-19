package com.barometric.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.room.Room
import androidx.work.*
import com.barometric.app.data.*
import com.barometric.app.widget.PressureWidget
import kotlinx.coroutines.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    private val database = Room.databaseBuilder(context, AppDatabase::class.java, "barometric.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).build()
    val settings = SettingsStore(context)
    val tracker = TrackerRepository(database, settings, WeatherApi()) { PressureWidget.updateAll(context.applicationContext) }
    val health = HealthRepository(context, tracker)
    val exporter = Exporter(context, tracker)
}

class BarometricApplication : Application() {
    val container by lazy { AppContainer(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        scope.launch { container.tracker.initialize() }
        val refresh = PeriodicWorkRequestBuilder<PressureRefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("pressure-refresh", ExistingPeriodicWorkPolicy.KEEP, refresh)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch { PressureWidget.updateAll(this@BarometricApplication) }
    }
}

class PressureRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val tracker = (applicationContext as BarometricApplication).container.tracker
        tracker.initialize()
        val location = tracker.currentLocation()
        val today = LocalDate.now(location.zone)
        val initial = tracker.dao.sources(location.id).none { it.lastSuccess != null }
        tracker.refresh(location, today.minusDays(if (initial) 89 else 6), today)
        if (tracker.dao.sources(location.id).all { it.error != null }) Result.retry() else Result.success()
    } catch (e: CancellationException) { throw e }
      catch (_: Exception) { Result.retry() }
}
