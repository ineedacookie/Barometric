package com.barometric.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barometric.app.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarometricApp(model: AppViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.controls.message
    LaunchedEffect(message) {
        if (message != null) { model.dismissMessage(); snackbar.showSnackbar(message) }
    }
    BackHandler(state.controls.settingsOpen || state.controls.detailDate != null) {
        if (state.controls.detailDate != null) model.detail(null) else model.settings(false)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.controls.settingsOpen) "Settings" else "barometric", fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp) },
                navigationIcon = { if (state.controls.settingsOpen) IconButton(onClick = { model.settings(false) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (!state.controls.settingsOpen) {
                        IconButton(onClick = { model.refresh(true) }, enabled = !state.controls.refreshing) {
                            if (state.controls.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, "Refresh pressure")
                        }
                        IconButton(onClick = { model.settings(true) }) { Icon(Icons.Rounded.Settings, "Settings") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!state.controls.settingsOpen) NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                listOf(AppTab.TODAY to Icons.AutoMirrored.Rounded.ShowChart, AppTab.CALENDAR to Icons.Rounded.CalendarMonth).forEach { (tab, icon) ->
                    NavigationBarItem(selected = state.controls.tab == tab, onClick = { model.tab(tab) },
                        icon = { Icon(icon, null) }, label = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.controls.settingsOpen -> SettingsScreen(state, model)
                state.controls.tab == AppTab.TODAY -> TodayScreen(state, model)
                state.controls.tab == AppTab.CALENDAR -> CalendarScreen(state, model)
                else -> InsightsScreen(state)
            }
        }
    }
    state.controls.detailDate?.let { date ->
        val location = state.historicalLocation(date)
        ModalBottomSheet(onDismissRequest = { model.detail(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")), style = MaterialTheme.typography.titleLarge)
                Text(location.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val daySummaries = state.summaries.filter { it.date == date && it.source.locationId == location.id }
                val sources = state.sources.filter { it.locationId == location.id }
                DailySourcePressure(daySummaries, sources, state.settings.unit)
                PressureChart(daySummaries, location, state.settings.unit, ChartRange.DAY, date, date, {}, sources)
                SymptomForm(state.entry(date), date, location, initiallyExpanded = true) { symptom, rating -> model.symptom(date, location, symptom, rating) }
            }
        }
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TodayScreen(state: UiState, model: AppViewModel) {
    val date = state.controls.date
    val location = state.location
    val sources = state.sources.filter { it.locationId == location.id }
    val chartDays = state.summaries.filter { it.source.locationId == location.id }
    var sourcesExpanded by rememberSaveable(location.id) { mutableStateOf(false) }
    PullToRefreshBox(isRefreshing = state.controls.refreshing, onRefresh = { model.refresh(true) }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartRange.entries.forEach { range -> FilterChip(selected = state.controls.range == range, onClick = { model.range(range) }, label = { Text(range.label, style = MaterialTheme.typography.labelSmall) }) }
                }
                if (state.controls.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading pressure history" })
                PressureChart(chartDays, location, state.settings.unit, state.controls.range, state.controls.anchor, date, model::inspect, sources)
            }
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.previousDay(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
                    Text(if (date == state.today) "Today · so far" else date.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { model.previousDay(1) }, enabled = date < state.today) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day") }
                    IconButton(onClick = { sourcesExpanded = !sourcesExpanded }) {
                        Icon(if (sourcesExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (sourcesExpanded) "Hide other pressure sources" else "Show other pressure sources")
                    }
                }
                val visibleSources = if (sourcesExpanded) sources else sources.filter { it.isCampus }
                DailySourcePressure(chartDays.filter { it.date == date }, visibleSources, state.settings.unit, showBasis = sourcesExpanded)
            }
            if (state.dataLoaded) SymptomForm(state.entry(date), date, location, initiallyExpanded = state.entry(date)?.symptomsComplete != true) { symptom, rating ->
                model.symptom(date, location, symptom, rating)
            }
        }
    }
}

@Composable
private fun DailySourcePressure(summaries: List<DaySummary>, sources: List<SourceEntity>, unit: PressureUnit, showBasis: Boolean = true) {
    val dark = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(unit.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("High", "Low", "Difference").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
    }
    sources.sortedBy { it.priority }.forEach { source ->
        val summary = summaries.find { it.source.id == source.id }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "${sourceLabel(source)}, high ${summary?.let { pressureText(it.high, unit) } ?: "unavailable"}, low ${summary?.let { pressureText(it.low, unit) } ?: "unavailable"}, difference ${summary?.let { pressureText(it.difference, unit) } ?: "unavailable"}, ${unit.label}${if (summary != null && !summary.complete && !summary.isToday) ", incomplete" else ""}"
        }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(sourceLabel(source) + if (summary != null && !summary.complete && !summary.isToday) " *" else "", Modifier.weight(1f), color = sourceColor(source, dark), style = MaterialTheme.typography.labelMedium)
            listOf(summary?.high, summary?.low, summary?.difference).forEach { value ->
                Text(value?.let { pressureText(it, unit) } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
    if (sources.isEmpty()) Text("No saved readings yet", style = MaterialTheme.typography.bodySmall)
    if (showBasis && sources.any { it.isCampus }) Text("USU: station pressure · NOAA and modeled: sea level", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (summaries.any { !it.complete && !it.isToday }) Text("* Incomplete day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SymptomForm(entry: DailyEntry?, date: LocalDate, location: LocationEntity, initiallyExpanded: Boolean,
    onSymptom: (Symptom, Int) -> Unit) {
    var expanded by rememberSaveable(date.toString(), location.id) { mutableStateOf(initiallyExpanded) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text("Symptoms")
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        if (expanded) "Dismiss symptom form" else "Open symptom form", Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                entry?.steps?.let { Text("${String.format(Locale.getDefault(), "%,d", it)} steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (expanded) {
                Symptom.entries.forEach { symptom ->
                    val value = symptom.value(entry)
                    var draft by remember(date, symptom, value) { mutableFloatStateOf((value ?: 0).toFloat()) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(symptom.label, Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium)
                        Slider(value = draft, onValueChange = { draft = it },
                            onValueChangeFinished = { onSymptom(symptom, draft.roundToInt().coerceIn(0, 5)) },
                            valueRange = 0f..5f, steps = 4, enabled = date <= LocalDate.now(location.zone),
                            colors = SliderDefaults.colors(thumbColor = ratingColor(draft), activeTrackColor = ratingColor(draft)),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("symptom-${symptom.name}").semantics {
                                contentDescription = "${symptom.label} severity"
                                stateDescription = value?.let { "$it of 5" } ?: "Not logged"
                                setProgress { requested ->
                                    if (date > LocalDate.now(location.zone)) false else {
                                        draft = requested.coerceIn(0f, 5f)
                                        onSymptom(symptom, draft.roundToInt())
                                        true
                                    }
                                }
                            })
                        Surface(onClick = { onSymptom(symptom, draft.roundToInt()) }, enabled = date <= LocalDate.now(location.zone),
                            modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 48.dp).semantics {
                                contentDescription = "Save ${symptom.label} as ${draft.roundToInt()}"
                            }, shape = RoundedCornerShape(10.dp), color = if (value == null) MaterialTheme.colorScheme.surfaceVariant else ratingColor(draft).copy(alpha = .3f)) {
                            Box(contentAlignment = Alignment.Center) { Text(if (value == null && draft == 0f) "—" else draft.roundToInt().toString(), style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
                Text("0 none · 5 severe · — unlogged", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CalendarScreen(state: UiState, model: AppViewModel) {
    val month = state.controls.calendarMonth
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value % 7
    val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    ScreenColumn {
        Text("Your days", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        Text("Tap a date to see pressure and rate your day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.calendarMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { model.calendarMonth(month.plusMonths(1)) }, enabled = month < YearMonth.from(state.today)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            repeat(cells / 7) { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(7) { weekday ->
                        val index = week * 7 + weekday - offset
                        val date = first.plusDays(index.toLong())
                        if (index < 0 || index >= month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(62.dp))
                        else {
                            val entry = state.entry(date)
                            val average = entry?.symptomAverage(state.settings)
                            val enabled = date <= state.today
                            Surface(onClick = { model.detail(date) }, enabled = enabled,
                                modifier = Modifier.weight(1f).heightIn(min = 76.dp).testTag("calendar-$date").semantics {
                                    contentDescription = "${date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))}, ${average?.let { "weighted symptom average ${String.format(Locale.getDefault(), "%.1f", it)}" } ?: "no weighted score"}. " +
                                        Symptom.entries.joinToString { "${it.label}: ${it.value(entry)?.toString() ?: "not logged"}" }
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = average?.let { ratingColor(it).copy(alpha = .28f) } ?: MaterialTheme.colorScheme.surfaceVariant,
                                border = if (date == state.today) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            ) {
                                Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .35f))
                                    Text(average?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Text("Weighted average · 0–5 · — no weighted score", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val entries = state.entries.filter { it.date.startsWith(month.toString()) && it.symptomAverage(state.settings) != null }
        Panel {
            Text("This month", style = MaterialTheme.typography.titleMedium)
            Text("${entries.size} days scored · ${entries.count { it.symptomAverage(state.settings) == 0.0 }} with a zero average", style = MaterialTheme.typography.bodyMedium)
            if (entries.isNotEmpty()) Text("Average rating ${String.format(Locale.getDefault(), "%.1f", entries.mapNotNull { it.symptomAverage(state.settings) }.average())}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val summaries = generateSequence(first) { it.plusDays(1) }.takeWhile { it <= month.atEndOfMonth() && it <= state.today }
                .mapNotNull { state.summary(it, state.historicalLocation(it)) }.toList()
            if (summaries.isNotEmpty()) {
                Text("Daily pressure range", style = MaterialTheme.typography.titleSmall)
                summaries.sortedByDescending { it.difference }.take(3).sortedBy { it.date }.forEach { day ->
                    Text("${day.date.format(DateTimeFormatter.ofPattern("MMM d"))} · ${pressureText(day.difference, state.settings.unit)} ${state.settings.unit.label}${if (!day.complete) " · partial" else ""}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Largest observed daily ranges this month.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InsightsScreen(state: UiState) {
    ScreenColumn {
        Text("Your patterns", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        Text("Personal associations, based on the days you log. They don’t establish a cause.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.insights.isEmpty()) Panel {
            Icon(Icons.Rounded.Insights, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text("A little more history", style = MaterialTheme.typography.titleLarge)
            Text("Insights appear after 30 rated days with complete matching data. Each location and pressure source is considered separately.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${state.entries.count { it.rating != null }} days rated so far", style = MaterialTheme.typography.labelLarge)
        }
        state.insights.forEach { insight ->
            Panel {
                Text(insight.metric, style = MaterialTheme.typography.titleMedium)
                Text("${insight.location} · ${insight.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val correlation = insight.correlation
                when {
                    insight.count < 30 -> {
                        LinearProgressIndicator(progress = { insight.count / 30f }, Modifier.fillMaxWidth())
                        Text("${insight.count} of 30 matching days", style = MaterialTheme.typography.bodyMedium)
                    }
                    correlation == null -> Text("There isn’t enough variation in these records to calculate an association.")
                    else -> {
                        val strength = when { abs(correlation) < .2 -> "little"; abs(correlation) < .4 -> "a weak"; abs(correlation) < .6 -> "a moderate"; else -> "a strong" }
                        Text("Your records show $strength ${if (abs(correlation) >= .2) if (correlation > 0) "positive " else "negative " else ""}association.", style = MaterialTheme.typography.bodyLarge)
                        Text("${insight.count} matching days · Spearman ρ = ${String.format(Locale.getDefault(), "%.2f", correlation)}", style = MaterialTheme.typography.bodySmall)
                        Text("An association can reflect other factors. It isn’t a prediction or diagnosis.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (insight.start != null && insight.end != null) Text("${insight.start} to ${insight.end}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(state: UiState, model: AppViewModel) {
    val context = LocalContext.current
    var delete by remember { mutableStateOf<String?>(null) }
    var pendingPermission by remember { mutableStateOf<String?>(null) }
    val health = model.container.health
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { permissions ->
        when (pendingPermission) {
            "steps" -> model.enableHealth(steps = health.stepsPermission in permissions)
        }
        if (permissions.isEmpty()) model.message("Health access wasn’t granted. Daily ratings work as usual.")
        pendingPermission = null
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
    ScreenColumn {
        Panel {
            Text("Location", style = MaterialTheme.typography.titleMedium)
            Text(state.location.label, style = MaterialTheme.typography.bodyLarge)
            Text("Weather days use ${state.location.timezone}. Past entries keep their original location.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = state.controls.searchQuery, onValueChange = model::search, label = { Text("Search a city or town") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Rounded.Search, null) })
            if (state.controls.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.controls.searchResults.forEach { location ->
                Surface(onClick = { model.choose(location) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(location.label, Modifier.padding(14.dp))
                }
            }
            if (state.location.id != LocationEntity.HYRUM.id) TextButton(onClick = { model.choose(LocationEntity.HYRUM) }) { Text("Reset to Hyrum, Utah") }
        }
        Panel {
            Text("Pressure units", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PressureUnit.entries.forEach { unit ->
                FilterChip(selected = state.settings.unit == unit, onClick = { model.unit(unit) }, label = { Text(unit.label) })
            } }
            Text("kPa = hPa ÷ 10. Campus station pressure uses the left axis; sea-level pressure uses the right.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Panel {
            Text("Daily symptom weights", style = MaterialTheme.typography.titleMedium)
            Text("Each weight is 0–10. Equal weights count equally; 0 excludes a symptom. Scores divide by the total weight of entered symptoms. Changes apply to all days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Symptom.entries.forEach { symptom ->
                val weight = state.settings.symptomWeight(symptom)
                var draft by remember(symptom, weight) { mutableFloatStateOf(weight.toFloat()) }
                Column {
                    Text("${if (symptom == Symptom.SOUND_SENSITIVITY) "Sound sensitivity" else symptom.label}: ${draft.roundToInt()}/10", style = MaterialTheme.typography.labelLarge)
                    Slider(value = draft, onValueChange = { draft = it.roundToInt().toFloat() },
                        onValueChangeFinished = { model.symptomWeight(symptom, draft.roundToInt()) },
                        valueRange = 0f..10f, steps = 9,
                        modifier = Modifier.fillMaxWidth().testTag("weight-${symptom.name}").semantics {
                            contentDescription = "${symptom.label} weight"
                            stateDescription = "${draft.roundToInt()} of 10"
                            setProgress { requested ->
                                draft = requested.coerceIn(0f, 10f).roundToInt().toFloat()
                                model.symptomWeight(symptom, draft.roundToInt())
                                true
                            }
                        })
                }
            }
            if (Symptom.entries.all { state.settings.symptomWeight(it) == 0 }) {
                Text("All weights are 0. Set at least one above 0 to calculate daily scores.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Panel {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            Text("Optional daily step totals, shared by Fitbit or another compatible app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val available = health.status() == HealthConnectClient.SDK_AVAILABLE
            if (!available) {
                Text("Install or update Health Connect to enable syncing.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
                        .onFailure { model.message("Open your app store and search for Health Connect.") }
                }) { Text("Get Health Connect") }
            }
            listOf("Steps" to state.settings.syncSteps).forEach { (label, enabled) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Switch(checked = enabled, enabled = available && !state.controls.working, onCheckedChange = { on ->
                        if (!on) {
                            model.enableHealth(steps = false)
                        } else {
                            pendingPermission = "steps"
                            permissionLauncher.launch(setOf(health.stepsPermission))
                        }
                    }, modifier = Modifier.semantics { contentDescription = "Sync $label" })
                }
            }
            Text("Syncs total steps for each calendar day when you open the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { delete = "health" }, enabled = !state.controls.working) { Text("Delete imported health data") }
        }
        Panel {
            Text("Your data", style = MaterialTheme.typography.titleMedium)
            Text("Symptoms and health totals stay on this phone. Export creates a JSON copy wherever you choose to save it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { exportLauncher.launch("barometric-${LocalDate.now()}.json") }, enabled = !state.controls.working, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Export JSON")
            }
            TextButton(onClick = { delete = "all" }, enabled = !state.controls.working) { Text("Delete all local data", color = MaterialTheme.colorScheme.error) }
            if (state.controls.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Panel {
            Text("Pressure sources", style = MaterialTheme.typography.titleMedium)
            Text("Station readings and model estimates stay separate. Free services can have gaps or delays.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.sources.filter { it.locationId == state.location.id }.forEach { source ->
                Text(source.name, style = MaterialTheme.typography.titleSmall)
                Text(if (source.modeled) "Modeled estimate for your location" else "${String.format(Locale.getDefault(), "%.1f", source.distanceKm)} km away · station ${source.stationId}", style = MaterialTheme.typography.bodySmall)
                if (source.isCampus) Text("Campus station pressure, sampled every three minutes. Sensor resolution: 0.01 kPa. Public detailed history: seven days; downloaded history stays on your phone. Station and sea-level readings use separate graph scales with equal spans.", style = MaterialTheme.typography.bodySmall)
                source.lastSuccess?.let { Text("Last synced ${Instant.ofEpochSecond(it).atZone(state.location.zone).format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                source.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            listOf("USU Campus Environmental Observatory" to "https://qanr.usu.edu/weather/graphical-data/barometric-pressure",
                "NOAA Aviation Weather Center" to "https://aviationweather.gov/data/api/",
                "Open-Meteo · CC BY 4.0" to "https://open-meteo.com/").forEach { (label, url) ->
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text(label) }
            }
            Text("Open-Meteo data includes ECMWF and ERA5 estimates. Location search uses GeoNames via Open-Meteo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Barometric 0.4.0 · personal, noncommercial use", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    delete?.let { type ->
        AlertDialog(onDismissRequest = { delete = null },
            title = { Text(if (type == "all") "Delete all local data?" else "Delete imported health data?") },
            text = { Text(if (type == "all") "This removes ratings, readings, health totals, and settings from this phone. Existing exported files are kept." else "This removes imported health totals from this app and turns syncing off. Your symptom ratings are kept.") },
            confirmButton = { TextButton(onClick = { delete = null; if (type == "all") model.deleteAll() else model.clearHealth() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Your health data", style = MaterialTheme.typography.headlineMedium)
            Text("Barometric reads daily step totals only when you enable them.")
            Text("Health data and ratings stay in this app’s storage on your phone. There are no accounts, ads, analytics, or automatic cloud backups. Health information is never sent to weather services.")
            Text("Weather providers receive the location you select. JSON export includes your ratings and enabled health totals; you choose where to save that file.")
            Text("You can stop syncing, remove imported health totals, or delete all local app data in Settings. You can also revoke permissions in Health Connect.")
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
