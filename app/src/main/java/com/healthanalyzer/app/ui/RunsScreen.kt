package com.healthanalyzer.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.data.DistanceFilter
import com.healthanalyzer.app.data.RangeFilter
import com.healthanalyzer.app.data.RunAnalysis
import com.healthanalyzer.app.data.RunDetail
import com.healthanalyzer.app.data.RunEntity
import com.healthanalyzer.app.data.RunFilter
import com.healthanalyzer.app.data.RunSummary
import com.healthanalyzer.app.data.RunTypeFilter
import com.healthanalyzer.app.data.SOURCE_GARMIN_FIT
import com.healthanalyzer.app.data.SOURCE_SAMSUNG
import com.healthanalyzer.app.data.SourceFilter
import com.healthanalyzer.app.data.Split
import com.healthanalyzer.app.ui.theme.GarminBlue
import com.healthanalyzer.app.ui.theme.PulseCoral
import com.healthanalyzer.app.ui.theme.RunnerTeal
import com.healthanalyzer.app.ui.theme.SamsungGreen
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")
private val detailDateFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy · HH:mm")

@Composable
fun RunsScreen(viewModel: RunsViewModel, activity: Activity, onAnalyzeRun: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    var helpGuide by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SetupGuide?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.progress?.let {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            state.message?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    }
                }
            }
            helpGuide?.let { GuideDialog(it, onDismiss = { helpGuide = null }) }
            val selected = state.selected
            if (selected != null) {
                BackHandler(onBack = viewModel::closeDetail)
                RunDetailView(selected, onBack = viewModel::closeDetail, onAnalyze = { onAnalyzeRun(selected.run.id) })
            } else {
                RunListView(
                    allRuns = runs,
                    filter = state.filter,
                    onFilter = viewModel::setFilter,
                    showDashboard = state.showDashboard,
                    onShowDashboard = viewModel::setShowDashboard,
                    permissionsGranted = state.permissionsGranted,
                    busy = state.busy,
                    onGrant = { viewModel.requestPermissions(activity) },
                    onSync = { full -> viewModel.sync(activity, full) },
                    onOpen = viewModel::open,
                    importMenu = { ImportMenuButton(viewModel, enabled = !state.busy, onHelp = { helpGuide = it }) },
                    garminConnected = state.garminConnected,
                    garminLastSync = state.garminLastSync,
                    onSyncGarmin = viewModel::syncGarmin,
                )
            }
        }
    }
}

@Composable
private fun RunListView(
    allRuns: List<RunEntity>,
    filter: RunFilter,
    onFilter: (RunFilter) -> Unit,
    showDashboard: Boolean,
    onShowDashboard: (Boolean) -> Unit,
    permissionsGranted: Boolean?,
    busy: Boolean,
    onGrant: () -> Unit,
    onSync: (full: Boolean) -> Unit,
    onOpen: (String) -> Unit,
    importMenu: @Composable () -> Unit,
    garminConnected: Boolean,
    garminLastSync: String?,
    onSyncGarmin: (full: Boolean) -> Unit,
) {
    val runs = remember(allRuns, filter) { filter.apply(allRuns) }
    val summary = remember(runs, filter) { RunSummary.of(runs, rangeDays = filter.range.days) }
    val totalDistanceKm = remember(allRuns) { allRuns.sumOf { (it.distanceM ?: 0f).toDouble() } / 1000 }
    val totalDurationSec = remember(allRuns) { allRuns.sumOf { it.durationSec } }


    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // App Hero Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcons.Runner(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column {
                            Text(
                                "HealthAnalyzer",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "AI-Powered Running Intelligence",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Quick Metric Highlights
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricHighlightCard(
                        title = "Distance",
                        value = "%.1f".format(totalDistanceKm),
                        unit = "km",
                        modifier = Modifier.weight(1f),
                    )
                    MetricHighlightCard(
                        title = "Workouts",
                        value = "${allRuns.size}",
                        unit = "runs",
                        modifier = Modifier.weight(1f),
                    )
                    MetricHighlightCard(
                        title = "Time",
                        value = hoursMinutes(totalDurationSec),
                        unit = "",
                        modifier = Modifier.weight(1f),
                    )
                }

                // Sync & Data Source Action Bar
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Connected Sources",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            importMenu()
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Samsung Health Sync
                            if (permissionsGranted != true) {
                                Button(
                                    onClick = onGrant,
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Grant Samsung Health", style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                Button(
                                    onClick = { onSync(false) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SamsungGreen,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text("Sync Samsung", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            // Garmin Connect Sync
                            if (garminConnected) {
                                OutlinedButton(
                                    onClick = { onSyncGarmin(false) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Sync Garmin", style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onSync(true) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Full Resync", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }

        // View Mode Switcher (List vs Performance Dashboard)
        item {
            SegmentedSwitcher(
                selectedIndex = if (showDashboard) 1 else 0,
                options = listOf("Runs List", "Performance Dashboard"),
                onSelect = { onShowDashboard(it == 1) },
            )
        }

        // Filters Bar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterRows(filter, onFilter, showSource = allRuns.any { it.sourceOrDefault != SOURCE_SAMSUNG })
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${runs.size} runs · ${"%.1f".format(summary.distanceKm)} km" +
                            if (!filter.isDefault) " (filtered)" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!filter.isDefault) {
                        TextButton(
                            onClick = { onFilter(RunFilter()) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (showDashboard) {
            item { Dashboard(summary) }
        } else {
            if (runs.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("No runs matching this filter", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Sync from Samsung Health, Garmin, or import .fit files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(runs.sortedByDescending { it.startEpochMs }, key = { it.id }) { run ->
                    RunCard(run, onClick = { onOpen(run.id) })
                }
            }
        }
    }
}

@Composable
private fun MetricHighlightCard(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (unit.isNotBlank()) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterRows(filter: RunFilter, onFilter: (RunFilter) -> Unit, showSource: Boolean) {
    @Composable
    fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, pick: (T) -> RunFilter) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { o ->
                val isSelected = o == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onFilter(pick(o)) },
                    label = {
                        Text(
                            label(o),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
    ChipRow(RunTypeFilter.entries, filter.type, { it.label }) { filter.copy(type = it) }
    ChipRow(RangeFilter.entries, filter.range, { it.label }) { filter.copy(range = it) }
    ChipRow(DistanceFilter.entries, filter.distance, { it.label }) { filter.copy(distance = it) }
    if (showSource || filter.source != SourceFilter.ALL) {
        ChipRow(SourceFilter.entries, filter.source, { it.label }) { filter.copy(source = it) }
    }
}

@Composable
private fun RunCard(run: RunEntity, onClick: () -> Unit) {
    val distanceKm = (run.distanceM ?: 0f) / 1000
    val isGarmin = run.sourceOrDefault == SOURCE_GARMIN_FIT
    val paceValue = pace(run)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: Date + Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatStart(run),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(
                        text = typeLabel(run.exerciseType),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusBadge(
                        text = if (isGarmin) "Garmin" else "Samsung",
                        containerColor = if (isGarmin) GarminBlue.copy(alpha = 0.15f) else SamsungGreen.copy(alpha = 0.15f),
                        contentColor = if (isGarmin) GarminBlue else SamsungGreen,
                        dotColor = if (isGarmin) GarminBlue else SamsungGreen,
                    )
                }
            }

            // Primary Stat Hero: Distance + Secondary Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Large distance
                Column(modifier = Modifier.weight(1.2f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "%.2f".format(distanceKm),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            " km",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }

                // Grid of stats: Time, Pace, HR
                Row(
                    modifier = Modifier.weight(1.8f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MiniStat(label = "Duration", value = RunAnalysis.formatDuration(run.durationSec))
                    MiniStat(label = "Pace", value = RunAnalysis.formatPace(paceValue))
                    run.meanHeartRate?.let {
                        MiniStat(label = "Avg HR", value = "♥ %.0f".format(it), highlightColor = PulseCoral)
                    }
                }

                AppIcons.ChevronRight(
                    modifier = Modifier.size(16.dp).padding(start = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            // Bottom telemetry details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    run.meanCadence?.let {
                        Text("⚡ %.0f spm".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    run.altitudeGain?.takeIf { it > 0 }?.let {
                        Text("▲ %.0fm".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    run.calories?.takeIf { it > 0 }?.let {
                        Text("%.0f kcal".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${run.sampleCount} pts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    highlightColor: Color? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = highlightColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Dashboard(s: RunSummary) {
    val dateFmt = DateTimeFormatter.ofPattern(if (s.monthlyBuckets) "MMM yyyy" else "MMM d")
    val dayFmt = DateTimeFormatter.ofPattern("MMM d, yy")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Runs", s.count.toString(), Modifier.weight(1f))
            StatTile("Distance km", "%.1f".format(s.distanceKm), Modifier.weight(1f))
            StatTile("Time", hoursMinutes(s.durationSec), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Avg pace", s.avgPaceSecPerKm?.let { RunAnalysis.formatPace(it) + "/km" } ?: "–", Modifier.weight(1f))
            StatTile("Avg HR", s.avgHeartRate?.let { "%.0f bpm".format(it) } ?: "–", Modifier.weight(1f))
            StatTile("Longest", "%.1f km".format(s.longestKm), Modifier.weight(1f))
        }
        ChartCard(
            title = if (s.monthlyBuckets) "Distance per month" else "Distance per week",
            points = s.volume.map { (d, km) -> ChartPoint(d.format(dateFmt), km) },
            format = { "%.1f km".format(it) },
            axisFormat = { "%.0f".format(it) },
            bars = true,
        )
        ChartCard(
            title = "Average pace per run",
            subtitle = "Higher is faster",
            points = s.pace.map { (d, v) -> ChartPoint(d.format(dayFmt), v) },
            format = { RunAnalysis.formatPace(it) + "/km" },
            axisFormat = { RunAnalysis.formatPace(it) },
            bars = false,
            invertY = true,
            tickSteps = listOf(15.0, 30.0, 60.0, 120.0, 300.0, 600.0),
        )
        ChartCard(
            title = "Average heart rate per run",
            points = s.heartRate.map { (d, v) -> ChartPoint(d.format(dayFmt), v) },
            format = { "%.0f bpm".format(it) },
            axisFormat = { "%.0f".format(it) },
            bars = false,
        )
        ChartCard(
            title = "VO2max estimate",
            points = s.vo2Max.map { (d, v) -> ChartPoint(d.format(dayFmt), v) },
            format = { "%.1f".format(it) },
            bars = false,
        )
    }
}

@Composable
private fun RunDetailView(detail: RunDetail, onBack: () -> Unit, onAnalyze: () -> Unit) {
    val run = detail.run
    val splits = remember(detail) { RunAnalysis.splits(detail.samples, run.distanceM) }
    val isGarmin = run.sourceOrDefault == SOURCE_GARMIN_FIT

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Back Bar & AI Analyze Action Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcons.ArrowLeft(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Button(
                    onClick = onAnalyze,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppIcons.Sparkles(modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        Text("Analyze with AI", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Workout Hero Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusBadge(
                        text = typeLabel(run.exerciseType),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                    StatusBadge(
                        text = if (isGarmin) "Garmin FIT" else "Samsung Health",
                        containerColor = if (isGarmin) GarminBlue.copy(alpha = 0.15f) else SamsungGreen.copy(alpha = 0.15f),
                        contentColor = if (isGarmin) GarminBlue else SamsungGreen,
                    )
                }
                Text(
                    formatStartDetail(run),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                run.title?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Category 1: Core Performance Overview
        item {
            DetailSectionCard(title = "Core Performance") {
                MetricRow("Distance", "%.2f km".format((run.distanceM ?: 0f) / 1000), isPrimary = true)
                MetricRow("Duration", RunAnalysis.formatDuration(run.durationSec), isPrimary = true)
                MetricRow("Average Pace", RunAnalysis.formatPace(pace(run)) + "/km", isPrimary = true)
                MetricRow("Active Calories", "%.0f kcal".format(run.calories))
                MetricRow("Step Count", run.stepCount?.toString() ?: "–")
            }
        }

        // Category 2: Heart Rate & Intensity
        item {
            DetailSectionCard(title = "Cardiovascular & Vitals") {
                MetricRow(
                    "Heart Rate (Avg / Max / Min)",
                    listOf(run.meanHeartRate, run.maxHeartRate, run.minHeartRate).fmt("%.0f bpm"),
                    accentColor = PulseCoral,
                )
                MetricRow("Estimated VO2max", run.vo2Max?.let { "%.1f ml/kg/min".format(it) } ?: "–")
                MetricRow(
                    "Training Effect (Aerobic / Anaerobic)",
                    listOf(run.aerobicTrainingEffect, run.anaerobicTrainingEffect).fmt("%.1f"),
                )
            }
        }

        // Category 3: Running Dynamics & Biomechanics
        item {
            DetailSectionCard(title = "Running Dynamics & Biomechanics") {
                MetricRow("Cadence (Avg / Max)", listOf(run.meanCadence, run.maxCadence).fmt("%.0f spm"))
                MetricRow("Ground Contact Time", run.groundContactMs?.let { "%.0f ms".format(it) } ?: "–")
                MetricRow("GCT Balance (Left)", run.groundContactBalancePct?.let { "%.1f %%".format(it) } ?: "–")
                MetricRow("Vertical Oscillation", run.verticalOscillationMm?.let { "%.1f cm".format(it / 10) } ?: "–")
                MetricRow("Vertical Ratio", run.verticalRatioPct?.let { "%.1f %%".format(it) } ?: "–")
                MetricRow("Average Step Length", run.stepLengthMm?.let { "%.2f m".format(it / 1000) } ?: "–")
                if (run.flightTimeMs != null) MetricRow("Flight Time", "%.0f ms".format(run.flightTimeMs))
                if (run.asymmetryPct != null) MetricRow("Left/Right Asymmetry", "%.1f %%".format(run.asymmetryPct))
                if (run.regularityPct != null) MetricRow("Stride Regularity", "%.0f %%".format(run.regularityPct))
                if (run.stiffnessKnM != null) MetricRow("Leg Stiffness", "%.1f kN/m".format(run.stiffnessKnM))
                if (run.sweatLossMl != null) MetricRow("Sweat Loss", "%.0f ml".format(run.sweatLossMl))
            }
        }

        // Category 4: Speed, Elevation & Power
        item {
            DetailSectionCard(title = "Speed & Elevation") {
                MetricRow(
                    "Speed (Avg / Max)",
                    listOf(run.meanSpeed, run.maxSpeed).map { it?.times(3.6f) }.fmt("%.1f km/h"),
                )
                MetricRow(
                    "Elevation (Gain / Loss)",
                    listOf(run.altitudeGain, run.altitudeLoss).fmt("%.0f m"),
                )
                MetricRow("Power (Avg / Max)", listOf(run.meanPower, run.maxPower).fmt("%.0f W"))
                MetricRow("GPS Telemetry Points", "${run.routePointCount} points (${run.sampleCount} time-series)")
            }
        }

        // Splits Table
        if (splits.isNotEmpty()) {
            item {
                SplitsCard(splits)
            }
        }
    }
}

@Composable
private fun DetailSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            content()
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    isPrimary: Boolean = false,
    accentColor: Color? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (isPrimary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (isPrimary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium,
            color = accentColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SplitsCard(splits: List<Split>) {
    val fastestPace = remember(splits) { splits.minOf { it.paceSecPerKm } }
    val slowestPace = remember(splits) { splits.maxOf { it.paceSecPerKm } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Kilometer Splits",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Splits Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Split", Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Pace", Modifier.width(80.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Avg HR", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Cadence", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            splits.forEach { s ->
                val ratio = if (slowestPace > fastestPace) {
                    ((slowestPace - s.paceSecPerKm) / (slowestPace - fastestPace)).toFloat()
                } else 1f
                val barColor = if (ratio > 0.6f) RunnerTeal else if (ratio > 0.3f) MaterialTheme.colorScheme.primary else PulseCoral.copy(alpha = 0.8f)

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (s.distanceM >= 999) "km ${s.index}" else "km ${s.index} (%.1f)".format(s.distanceM / 1000),
                            Modifier.width(60.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            RunAnalysis.formatPace(s.paceSecPerKm),
                            Modifier.width(80.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            s.meanHeartRate?.let { "♥ %.0f".format(it) } ?: "–",
                            Modifier.width(70.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseCoral,
                        )
                        Text(
                            s.meanCadence?.let { "%.0f spm".format(it) } ?: "–",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Visual pace bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((0.25f + ratio * 0.75f).coerceIn(0.1f, 1f))
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(barColor),
                        )
                    }
                }
            }
        }
    }
}

private fun List<Float?>.fmt(pattern: String): String =
    if (all { it == null }) "–" else joinToString(" / ") { v -> v?.let { pattern.format(it) } ?: "–" }

private fun pace(run: RunEntity): Double {
    val d = run.distanceM ?: return 0.0
    return if (d > 0) run.durationSec / d.toDouble() * 1000 else 0.0
}

private fun formatStart(run: RunEntity): String {
    val zone = run.zoneOffsetSec?.let { ZoneOffset.ofTotalSeconds(it) } ?: ZoneId.systemDefault()
    return Instant.ofEpochMilli(run.startEpochMs).atZone(zone).format(dateFormat)
}

private fun formatStartDetail(run: RunEntity): String {
    val zone = run.zoneOffsetSec?.let { ZoneOffset.ofTotalSeconds(it) } ?: ZoneId.systemDefault()
    return Instant.ofEpochMilli(run.startEpochMs).atZone(zone).format(detailDateFormat)
}

private fun typeLabel(type: String) = when (type) {
    "RUNNING" -> "Outdoor Run"
    "TREADMILL" -> "Treadmill"
    "TRACK_RUNNING" -> "Track Run"
    else -> type
}

private fun hoursMinutes(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
