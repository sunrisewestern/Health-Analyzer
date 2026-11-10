package com.healthanalyzer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.ai.AiProviderEntity
import com.healthanalyzer.app.ai.PlanInfo
import com.healthanalyzer.app.ai.PlanWorkoutEntity
import com.healthanalyzer.app.ai.PromptRenderer
import com.healthanalyzer.app.ai.TrainingPlan
import com.healthanalyzer.app.ai.WorkoutType
import com.healthanalyzer.app.data.RunAnalysis
import com.healthanalyzer.app.data.RunEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private fun WorkoutType.color(): Color = when (this) {
    WorkoutType.EASY -> Color(0xFF2E9E6B)
    WorkoutType.LONG -> Color(0xFF3B7DD8)
    WorkoutType.TEMPO -> Color(0xFFD9822B)
    WorkoutType.INTERVALS -> Color(0xFFD9534F)
    WorkoutType.RECOVERY -> Color(0xFF3AA0A6)
    WorkoutType.RACE -> Color(0xFF8E5BD0)
    WorkoutType.CROSS -> Color(0xFF7A8594)
}

private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun km(v: Double): String = if (v >= 100) "%.0f".format(v) else "%.1f".format(v).removeSuffix(".0")
private fun runKm(r: RunEntity) = (r.distanceM ?: 0f) / 1000.0

/** Month calendar of planned workouts and the runs actually done, with AI plan generation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(vm: AiViewModel, onOpenSettings: () -> Unit) {
    val workouts by vm.planWorkouts.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val state by vm.plan.collectAsStateWithLifecycle()
    val info by vm.planInfo.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()

    val today = remember { LocalDate.now() }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    var showGenerate by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var summaryOpen by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)

    val planByDate = remember(workouts) { workouts.groupBy { it.date } }
    val runsByDate = remember(runs) { runs.groupBy { PromptRenderer.localStart(it).toLocalDate() } }
    val hasUpcoming = remember(workouts, today) { workouts.any { it.date >= today.toString() } }
    val recapSettings by vm.recapSettings.collectAsStateWithLifecycle()
    val recap by vm.recap.collectAsStateWithLifecycle()
    val recapRunning by vm.recapRunning.collectAsStateWithLifecycle()
    val todaysPlan = planByDate[today.toString()].orEmpty()

    // Writes the briefing when the day, today's workouts or the place change (cached otherwise).
    androidx.compose.runtime.LaunchedEffect(recapSettings, todaysPlan, providers.isNotEmpty(), state.generating) {
        if (recapSettings.enabled && !state.generating) vm.refreshRecap()
    }

    fun select(date: LocalDate) {
        selectedText = date.toString()
        monthText = YearMonth.from(date).toString()
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { AppIcons.Calendar(modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f)) {
                Text("Training Plan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "A personal running plan built from your data",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.generating) {
                Button(onClick = { showGenerate = true }, shape = RoundedCornerShape(10.dp)) {
                    Text(if (hasUpcoming) "New plan" else "Create plan")
                }
            }
        }

        if (recapSettings.enabled) {
            TodayCard(
                todaysPlan = todaysPlan,
                runsToday = runsByDate[today].orEmpty(),
                recap = recap,
                running = recapRunning,
                hasPlace = recapSettings.place != null,
                onRefresh = { vm.refreshRecap(force = true) },
                onOpenDay = { select(today) },
            )
        }

        if (state.generating) {
            PlanCard {
                Text("Your coach is writing the plan…", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.receivedChars == 0) "Waiting for the model (thinking models can take a minute)"
                        else "${state.receivedChars} characters received",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::stopPlan) { Text("Stop") }
                }
            }
        }
        state.error?.let { error ->
            PlanCard(border = MaterialTheme.colorScheme.error) {
                Text("Couldn't create the plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text(error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (providers.isEmpty()) {
                        Button(onClick = { vm.dismissPlanError(); onOpenSettings() }, shape = RoundedCornerShape(10.dp)) { Text("Configure in Settings") }
                    } else {
                        Button(onClick = { vm.dismissPlanError(); showGenerate = true }, shape = RoundedCornerShape(10.dp)) { Text("Try again") }
                    }
                    TextButton(onClick = vm::dismissPlanError) { Text("Dismiss") }
                }
            }
        }

        // Calendar
        PlanCard(padding = 10) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { monthText = month.minusMonths(1).toString() }) { Text("‹", fontSize = 22.sp) }
                Text(
                    month.format(MONTH_TITLE),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { monthText = month.plusMonths(1).toString() }) { Text("›", fontSize = 22.sp) }
            }
            Row {
                DayOfWeek.entries.forEach { d ->
                    Text(
                        d.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekCount = ((java.time.temporal.ChronoUnit.DAYS.between(first, month.atEndOfMonth()) / 7) + 1).toInt()
            for (w in 0 until weekCount) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (i in 0 until 7) {
                        val date = first.plusDays((w * 7 + i).toLong())
                        DayCell(
                            date = date,
                            inMonth = YearMonth.from(date) == month,
                            isToday = date == today,
                            isPast = date.isBefore(today),
                            selected = date == selected,
                            planned = planByDate[date.toString()].orEmpty(),
                            runs = runsByDate[date].orEmpty(),
                            onClick = { select(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                ) {
                    WorkoutType.entries.filter { t -> workouts.any { it.type == t.name } }.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(t.color()))
                            Text(t.short, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (selected != today || YearMonth.from(today) != month) {
                    TextButton(onClick = { select(today) }) { Text("Today") }
                }
            }
        }

        // Selected week and day
        val weekStart = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val weekPlanned = TrainingPlan.plannedKm(workouts, weekStart, weekEnd)
        val weekDone = (0L..6L).sumOf { d -> runsByDate[weekStart.plusDays(d)].orEmpty().sumOf { runKm(it) } }
        PlanCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected.format(DAY_TITLE) + if (selected == today) " · today" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Week of ${weekStart.format(DateTimeFormatter.ofPattern("d MMM"))}: planned ${km(weekPlanned)} km · done ${km(weekDone)} km",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (weekPlanned > 0) {
                LinearProgressIndicator(
                    progress = { (weekDone / weekPlanned).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            }

            val planned = planByDate[selected.toString()].orEmpty()
            val done = runsByDate[selected].orEmpty().sortedBy { it.startEpochMs }
            if (planned.isEmpty() && done.isEmpty()) {
                Text(
                    when {
                        workouts.isEmpty() -> "No plan yet. Tap Create plan and your AI coach builds one from your runs, profile and baseline."
                        selected.isBefore(today) -> "No run recorded."
                        else -> "Rest day – nothing planned."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            planned.forEach { w ->
                WorkoutRow(w, done = done.isNotEmpty(), missed = done.isEmpty() && selected.isBefore(today), onDelete = { vm.deletePlanWorkout(w.id) })
            }
            done.forEach { r -> RunRow(r) }
        }

        // Coach's summary
        if (info.summary.isNotBlank() && hasUpcoming) {
            PlanCard {
                Row(Modifier.fillMaxWidth().clickable { summaryOpen = !summaryOpen }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Coach's notes on this plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(planCaption(info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (summaryOpen) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (summaryOpen) {
                    if (info.goal.isNotBlank()) {
                        Text("Goal: ${info.goal}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MarkdownText(info.summary)
                }
            }
        }
        if (hasUpcoming && !state.generating) {
            OutlinedButton(onClick = { confirmClear = true }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Clear upcoming plan")
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showGenerate) {
        GeneratePlanDialog(
            info = info,
            providers = providers,
            defaultProviderId = vm.analyze.collectAsStateWithLifecycle().value.providerId,
            replacesPlan = hasUpcoming,
            onDismiss = { showGenerate = false },
            onOpenSettings = { showGenerate = false; onOpenSettings() },
            onGenerate = { providerId, goal, weeks, perWeek ->
                showGenerate = false
                vm.generatePlan(providerId, goal, weeks, perWeek)
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear upcoming plan?") },
            text = { Text("Removes all planned workouts from today on. Past days and your recorded runs stay.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearUpcomingPlan() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TodayCard(
    todaysPlan: List<PlanWorkoutEntity>,
    runsToday: List<RunEntity>,
    recap: com.healthanalyzer.app.ai.Recap,
    running: Boolean,
    hasPlace: Boolean,
    onRefresh: () -> Unit,
    onOpenDay: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // A briefing from an earlier day is hidden until today's is written.
    val recap = if (recap.key.startsWith(LocalDate.now().toString())) recap else com.healthanalyzer.app.ai.Recap()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        LocalDate.now().format(DAY_TITLE),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (!running) TextButton(onClick = onRefresh) { Text("Refresh") }
            }

            // Facts first, so the card is useful before (or without) the AI text.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenDay),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val w = todaysPlan.firstOrNull()
                Box(Modifier.size(10.dp).clip(CircleShape).background(w?.workoutType?.color() ?: scheme.outline))
                Text(
                    when {
                        w == null -> "Rest day"
                        else -> todaysPlan.joinToString(" + ") { p ->
                            listOfNotNull(p.title, p.target.takeIf { it.isNotBlank() }).joinToString(" · ")
                        }
                    } + if (runsToday.isNotEmpty()) "  ✓ ran ${km(runsToday.sumOf { runKm(it) })} km" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (recap.weatherLine.isNotBlank()) {
                Text(recap.weatherLine, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else if (!hasPlace) {
                Text(
                    "Add your city in Settings → Training Plan to include the weather.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            if (running && recap.text.isBlank()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (hasPlace) "Your coach is checking today's plan and weather…" else "Your coach is checking today's plan…",style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (recap.text.isNotBlank()) MarkdownText(recap.text)
            if (!running) recap.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
        }
    }
}

private fun planCaption(info: PlanInfo): String = listOfNotNull(
    info.createdEpochMs.takeIf { it > 0 }?.let { "Written ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()}" },
    info.source.takeIf { it.isNotBlank() },
).joinToString(" · ")

@Composable
private fun PlanCard(border: Color? = null, padding: Int = 14, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, border ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(padding.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isPast: Boolean,
    selected: Boolean,
    planned: List<PlanWorkoutEntity>,
    runs: List<RunEntity>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .graphicsLayer { alpha = if (inMonth) 1f else 0.35f }
            .clip(shape)
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, scheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .height(50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) scheme.onPrimary else scheme.onSurface,
            )
        }
        val doneKm = runs.sumOf { runKm(it) }
        val workout = planned.firstOrNull()
        if (workout != null) {
            // Filled = to do or done (✓); faded = a past day with no run.
            val missed = isPast && runs.isEmpty()
            val label = workout.distanceKm?.let { km(it.toDouble()) } ?: workout.workoutType.short
            Chip(
                text = if (runs.isNotEmpty()) "✓ ${km(doneKm)}" else label,
                background = workout.workoutType.color().copy(alpha = if (missed) 0.3f else 1f),
                content = Color.White,
            )
        } else if (runs.isNotEmpty()) {
            Chip(text = km(doneKm), background = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Chip(text: String, background: Color, content: Color) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(background).padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun WorkoutRow(w: PlanWorkoutEntity, done: Boolean, missed: Boolean, onDelete: () -> Unit) {
    val type = w.workoutType
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 3.dp).width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(type.color()))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(w.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                when {
                    done -> StatusBadge("Done", containerColor = type.color().copy(alpha = 0.18f), contentColor = MaterialTheme.colorScheme.onSurface, dotColor = type.color())
                    missed -> StatusBadge("Missed")
                }
            }
            val facts = listOfNotNull(
                type.label,
                w.distanceKm?.let { "${km(it.toDouble())} km" },
                w.durationMin?.let { "$it min" },
            ).joinToString(" · ")
            Text(facts, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (w.target.isNotBlank()) Text("Target: ${w.target}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (w.notes.isNotBlank()) Text(w.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Remove",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDelete).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun RunRow(r: RunEntity) {
    val dist = runKm(r)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppIcons.Runner(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(
                "Ran ${"%.2f".format(dist)} km in ${RunAnalysis.formatDuration(r.durationSec)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                listOfNotNull(
                    PromptRenderer.localStart(r).format(TIME),
                    PromptRenderer.typeLabel(r.exerciseType),
                    if (dist > 0) "${RunAnalysis.formatPace(r.durationSec / dist)}/km" else null,
                    r.meanHeartRate?.let { "%.0f bpm".format(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GeneratePlanDialog(
    info: PlanInfo,
    providers: List<AiProviderEntity>,
    defaultProviderId: Long?,
    replacesPlan: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onGenerate: (providerId: Long?, goal: String, weeks: Int, runsPerWeek: Int) -> Unit,
) {
    var goal by rememberSaveable { mutableStateOf(info.goal) }
    var weeks by rememberSaveable { mutableStateOf(com.healthanalyzer.app.ai.DEFAULT_WEEKS) }
    var perWeek by rememberSaveable { mutableStateOf(info.runsPerWeek.takeIf { it in TrainingPlan.RUNS_PER_WEEK_OPTIONS } ?: 4) }
    var providerId by rememberSaveable { mutableStateOf(defaultProviderId) }
    val provider = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a running plan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (providers.isEmpty()) {
                    Text("Add an AI provider in Settings first – the plan is written by your AI model.")
                } else {
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text("Goal and constraints") },
                        placeholder = { Text("e.g. 10 km under 55 min on 20 Dec. Long run on Sunday, no running on Wednesday.") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Picker("Length", weeks, TrainingPlan.WEEK_OPTIONS, { if (it == 1) "1 week" else "$it weeks" }, { weeks = it }, Modifier.weight(1f))
                        Picker("Runs per week", perWeek, TrainingPlan.RUNS_PER_WEEK_OPTIONS, { "$it runs" }, { perWeek = it }, Modifier.weight(1f))
                    }
                    Picker(
                        label = "AI model",
                        selected = provider,
                        options = providers,
                        display = { "${it.name} (${it.model.ifBlank { it.protocol.label }})" },
                        onSelect = { providerId = it.id },
                    )
                    Text(
                        "Sent to the model: your profile, baseline, 12-month running stats, 12 weeks of weekly totals and the last 28 days of runs." +
                            if (replacesPlan) "\n\nThis replaces the workouts planned from today on." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (providers.isEmpty()) TextButton(onClick = onOpenSettings) { Text("Open Settings") }
            else TextButton(onClick = { onGenerate(provider?.id, goal, weeks, perWeek) }) { Text("Create plan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
