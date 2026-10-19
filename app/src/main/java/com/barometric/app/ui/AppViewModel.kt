package com.barometric.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.barometric.app.AppContainer
import com.barometric.app.data.*
import com.barometric.app.domain.*
import com.barometric.app.widget.PressureWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

enum class AppTab { TODAY, CALENDAR, INSIGHTS }
enum class ChartRange(val label: String, val days: Long) { DAY("Day", 1), TWO_DAYS("2 days", 2), WEEK("Week", 7), MONTH("Month", 30), QUARTER("90 days", 90) }

data class Controls(
    val tab: AppTab = AppTab.TODAY,
    val settingsOpen: Boolean = false,
    val date: LocalDate = LocalDate.now(LocationEntity.HYRUM.zone),
    val anchor: LocalDate = date,
    val range: ChartRange = ChartRange.DAY,
    val calendarMonth: YearMonth = YearMonth.from(date),
    val detailDate: LocalDate? = null,
    val refreshing: Boolean = false,
    val historyLoading: Boolean = false,
    val working: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<LocationEntity> = emptyList(),
    val searching: Boolean = false,
    val message: String? = null,
)

private data class DataContext(val settings: AppSettings, val locations: List<LocationEntity>, val periods: List<LocationPeriod>, val sources: List<SourceEntity>)
private data class WeatherState(val context: DataContext, val summaries: List<DaySummary>)

data class UiState(
    val settings: AppSettings = AppSettings(),
    val locations: List<LocationEntity> = listOf(LocationEntity.HYRUM),
    val periods: List<LocationPeriod> = emptyList(),
    val sources: List<SourceEntity> = emptyList(),
    val summaries: List<DaySummary> = emptyList(),
    val entries: List<DailyEntry> = emptyList(),
    val controls: Controls = Controls(),
    val insights: List<Insight> = emptyList(),
    val dataLoaded: Boolean = false,
) {
    val location: LocationEntity get() = locations.find { it.id == settings.locationId } ?: LocationEntity.HYRUM
    val today: LocalDate get() = LocalDate.now(location.zone)
    fun entry(date: LocalDate): DailyEntry? = entries.find { it.date == date.toString() }
    fun historicalLocation(date: LocalDate): LocationEntity {
        val id = entry(date)?.locationId ?: periods.lastOrNull { it.startDate <= date.toString() }?.locationId
        return locations.find { it.id == id } ?: location
    }
    fun summary(date: LocalDate, location: LocationEntity = this.location, sourceId: String? = null): DaySummary? =
        Pressure.preferred(summaries.filter { it.date == date && it.source.locationId == location.id }, sourceId)
}

class AppViewModel(val container: AppContainer) : ViewModel() {
    private val tracker = container.tracker
    private val controls = MutableStateFlow(Controls())
    private var searchJob: Job? = null
    private var historyJob: Job? = null
    private var refreshJob: Job? = null
    private var lastForegroundDay: LocalDate? = null
    private var historyRequest = 0

    private val context = combine(container.settings.flow, tracker.dao.observeLocations(), tracker.dao.observePeriods(), tracker.dao.observeSources()) {
        settings, locations, periods, sources -> DataContext(settings, locations, periods, sources.filter { it.isActive })
    }
    private val weather = combine(context, tracker.dao.observeReadings(0)) { data, readings ->
        withContext(Dispatchers.Default) {
            WeatherState(data, data.locations.flatMap { Pressure.summarize(it, data.sources, readings) })
        }
    }
    val state: StateFlow<UiState> = combine(weather, tracker.dao.observeEntries(), controls) { weather, entries, controls ->
        withContext(Dispatchers.Default) {
            UiState(weather.context.settings, weather.context.locations, weather.context.periods, weather.context.sources,
                weather.summaries, entries, controls, dataLoaded = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init { foreground() }

    fun foreground() {
        viewModelScope.launch {
            tracker.initialize()
            val today = LocalDate.now(tracker.currentLocation().zone)
            if (lastForegroundDay != today) {
                lastForegroundDay = today
                controls.update { it.copy(date = today, anchor = today, calendarMonth = YearMonth.from(today)) }
            }
            refresh()
        }
    }

    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            controls.update { it.copy(refreshing = true) }
            try {
                val location = tracker.currentLocation()
                val today = LocalDate.now(location.zone)
                val date = controls.value.date.coerceAtMost(today)
                controls.update { it.copy(date = date, anchor = it.anchor.coerceAtMost(today)) }
                val view = controls.value
                tracker.refreshForView(location, view.anchor.minusDays(view.range.days - 1), view.anchor, force)
                container.health.sync()?.let(::message)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message("Couldn’t refresh. Your saved data is available.")
            } finally { controls.update { it.copy(refreshing = false) } }
        }
    }

    fun tab(tab: AppTab) { controls.update {
        if (tab == AppTab.TODAY) it.copy(tab = tab, date = state.value.today, anchor = state.value.today, detailDate = null)
        else it.copy(tab = tab, detailDate = null)
    } }
    fun settings(open: Boolean) { controls.update { it.copy(settingsOpen = open) } }
    fun range(range: ChartRange) {
        controls.update { it.copy(range = range, date = it.anchor) }
        loadHistory(controls.value.anchor.minusDays(range.days - 1), controls.value.anchor, state.value.location)
    }
    fun inspect(date: LocalDate) { controls.update { it.copy(date = date.coerceAtMost(state.value.today)) } }
    fun previousDay(delta: Long) { controls.update {
        val date = it.date.plusDays(delta).coerceAtMost(state.value.today)
        it.copy(date = date, anchor = date)
    }; loadHistory(controls.value.date.minusDays(controls.value.range.days - 1), controls.value.date, state.value.location) }

    fun calendarMonth(month: YearMonth) {
        controls.update { it.copy(calendarMonth = month) }
        val last = minOf(month.atEndOfMonth(), state.value.today)
        if (month.atDay(1) <= last) {
            historyJob?.cancel()
            historyJob = viewModelScope.launch {
                // Fetch each historical location represented in the month without reassigning entries.
                val contexts = generateSequence(month.atDay(1)) { it.plusDays(1) }.takeWhile { it <= last }
                    .map { state.value.historicalLocation(it) }.distinctBy { it.id }.toList()
                try { contexts.forEach { tracker.refresh(it, month.atDay(1), last) } }
                catch (e: Exception) { if (e is CancellationException) throw e; message("History is unavailable right now. Saved readings are kept.") }
            }
        }
    }
    fun detail(date: LocalDate?) {
        controls.update { it.copy(detailDate = date) }
        date?.let { loadHistory(it, it, state.value.historicalLocation(it)) }
    }
    private fun loadHistory(from: LocalDate, to: LocalDate, location: LocationEntity) {
        val request = ++historyRequest
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            controls.update { it.copy(historyLoading = true) }
            try { tracker.refresh(location, from, to, fullResolution = true) }
            catch (e: Exception) { if (e is CancellationException) throw e; message("History is unavailable right now. Saved readings are kept.") }
            finally { if (request == historyRequest) controls.update { it.copy(historyLoading = false) } }
        }
    }
    fun rate(date: LocalDate, location: LocationEntity, rating: Int?) = action {
        tracker.rate(date, location, rating)
    }
    fun symptom(date: LocalDate, location: LocationEntity, symptom: Symptom, rating: Int) = action {
        tracker.symptom(date, location, symptom, rating)
    }
    fun unit(unit: PressureUnit) = action {
        container.settings.setUnit(unit)
        PressureWidget.updateAll(container.applicationContext)
    }
    fun symptomWeight(symptom: Symptom, weight: Int) = action {
        container.settings.setSymptomWeight(symptom, weight)
    }
    fun choose(location: LocationEntity) = action {
        refreshJob?.cancelAndJoin()
        historyJob?.cancelAndJoin()
        tracker.selectLocation(location)
        val today = LocalDate.now(location.zone)
        controls.update { it.copy(date = today, anchor = today, calendarMonth = YearMonth.from(today), searchQuery = "", searchResults = emptyList()) }
        refresh(true)
    }

    fun search(query: String) {
        controls.update { it.copy(searchQuery = query, searchResults = emptyList(), searching = query.trim().length >= 2) }
        searchJob?.cancel()
        if (query.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(400)
            try {
                val results = tracker.api.search(query)
                controls.update { it.copy(searchResults = results, searching = false) }
                if (results.isEmpty()) message("No matching locations found.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                controls.update { it.copy(searching = false) }
                message("Location search needs an internet connection.")
            }
        }
    }
    fun enableHealth(steps: Boolean? = null) = action {
        container.health.enabled(steps)
        container.health.sync()?.let(::message)
    }
    fun clearHealth() = action { tracker.clearHealth(); message("Imported health data deleted.") }
    fun deleteAll() = action {
        refreshJob?.cancelAndJoin(); historyJob?.cancelAndJoin()
        tracker.deleteAll()
        controls.value = Controls(settingsOpen = true, working = true, message = "Local data deleted.")
    }
    fun export(uri: android.net.Uri) = action { container.exporter.export(uri); message("JSON export saved.") }
    fun message(message: String) { controls.update { it.copy(message = message) } }
    fun dismissMessage() { controls.update { it.copy(message = null) } }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            controls.update { it.copy(working = true) }
            try { block() }
            catch (e: Exception) { if (e is CancellationException) throw e; message("Couldn’t complete that action. Please try again.") }
            finally { controls.update { it.copy(working = false) } }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }
}
