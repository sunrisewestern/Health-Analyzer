package com.healthanalyzer.app.ai

import com.healthanalyzer.app.data.RunAnalysis
import com.healthanalyzer.app.data.RunEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/** Training-review prompt text: weekly load, per-run spacing, plan vs actual. Pure Kotlin, no Android. */
object TrainingLoad {

    private const val WALK_PACE_SEC_PER_KM = 600.0
    private const val WALK_CADENCE_SPM = 130f
    const val WALK_RULE = "a session counts as a walk when its pace is slower than 10'00\"/km or its cadence is under 130 spm"

    /** Treadmill and outdoor sessions recorded as runs but done at walking speed. */
    fun isWalk(r: RunEntity): Boolean {
        val km = km(r)
        val slow = km > 0 && r.durationSec / km > WALK_PACE_SEC_PER_KM
        return slow || (r.meanCadence?.let { it > 0 && it < WALK_CADENCE_SPM } ?: false)
    }

    fun label(r: RunEntity): String = when {
        !isWalk(r) -> PromptRenderer.typeLabel(r.exerciseType)
        r.exerciseType == "TREADMILL" -> "Treadmill walk"
        else -> "Walk"
    }

    /**
     * Weekly totals with runs and walks kept apart, then weekly minutes per heart-rate zone.
     * [zoneSeconds] maps run id → seconds in each of the 5 zones; runs without heart-rate rows are absent.
     */
    fun weekly(
        runs: List<RunEntity>,
        weeks: Int,
        zones: HrZones.ZoneSet?,
        zoneSeconds: Map<String, DoubleArray>,
        today: LocalDate = LocalDate.now(),
    ): String {
        val start = monday(today).minusWeeks((weeks - 1).toLong())
        val byWeek = runs.groupBy { monday(date(it)) }
        return buildString {
            appendLine("Weekly totals, last $weeks weeks (weeks start Monday; the last week is still in progress).")
            appendLine("Runs and walks are counted separately: $WALK_RULE.")
            appendLine("week of | runs | run km | run time | avg run pace/km | avg run HR | longest run km | walks | walk km | walk time")
            for (i in 0 until weeks) {
                val week = start.plusWeeks(i.toLong())
                val (walks, real) = byWeek[week].orEmpty().partition { isWalk(it) }
                val dist = real.sumOf { km(it) }
                val time = real.sumOf { it.durationSec }
                appendLine(
                    listOf(
                        week.toString(),
                        real.size.toString(),
                        "%.1f".format(dist),
                        RunAnalysis.formatDuration(time),
                        if (dist > 0) RunAnalysis.formatPace(time / dist) else "–",
                        weightedHr(real)?.let { "%.0f".format(it) } ?: "–",
                        real.maxOfOrNull { km(it) }?.let { "%.1f".format(it) } ?: "–",
                        walks.size.toString(),
                        "%.1f".format(walks.sumOf { km(it) }),
                        RunAnalysis.formatDuration(walks.sumOf { it.durationSec }),
                    ).joinToString(" | "),
                )
            }
            if (zones != null && zoneSeconds.isNotEmpty()) {
                appendLine()
                appendLine("Weekly minutes in each heart-rate zone, runs and walks together (${zones.header.removePrefix("Time in HR zones ").trim('(', ')')}: ${zones.names.joinToString(" · ")}).")
                appendLine("Load = Z1 min × 1 + Z2 × 2 + Z3 × 3 + Z4 × 4 + Z5 × 5.")
                appendLine("week of | Z1 | Z2 | Z3 | Z4 | Z5 | load | load vs previous week | sessions with HR")
                var previous: Double? = null
                for (i in 0 until weeks) {
                    val week = start.plusWeeks(i.toLong())
                    val list = byWeek[week].orEmpty()
                    val withHr = list.mapNotNull { zoneSeconds[it.id] }
                    val min = DoubleArray(5) { z -> withHr.sumOf { it[z] } / 60 }
                    val load = min.withIndex().sumOf { (z, m) -> m * (z + 1) }
                    val change = previous?.takeIf { it > 0 }?.let { "%+.0f%%".format((load - it) / it * 100) } ?: "–"
                    appendLine(
                        (listOf(week.toString()) + min.map { "%.0f".format(it) } +
                            listOf("%.0f".format(load), change, "${withHr.size} of ${list.size}")).joinToString(" | "),
                    )
                    previous = load
                }
            }
        }.trimEnd()
    }

    /**
     * One line per session since [since]. [runs] should reach back before [since] so the first
     * line can say how long the break before it was.
     */
    fun recentRuns(runs: List<RunEntity>, days: Int, since: LocalDate): String {
        val sorted = runs.sortedBy { it.startEpochMs }
        val shown = sorted.filter { !date(it).isBefore(since) }
        if (shown.isEmpty()) return "(no runs in the last $days days)"
        return buildString {
            appendLine("Sessions in the last $days days (${shown.size}; $WALK_RULE):")
            appendLine("date | type | km | moving time | paused | pace/km | avg HR | max HR | cadence | elev+ m | VO2max | days since previous session")
            shown.forEach { r ->
                val previous = sorted.lastOrNull { it.startEpochMs < r.startEpochMs }
                val paused = (r.endEpochMs - r.startEpochMs) / 1000 - r.durationSec
                appendLine(
                    listOf(
                        PromptRenderer.localStart(r).format(DATE_TIME),
                        label(r),
                        "%.2f".format(km(r)),
                        RunAnalysis.formatDuration(r.durationSec),
                        if (paused >= 30) RunAnalysis.formatDuration(paused) else "–",
                        if (km(r) > 0) RunAnalysis.formatPace(r.durationSec / km(r)) else "–",
                        r.meanHeartRate.fmt0(),
                        r.maxHeartRate.fmt0(),
                        r.meanCadence.fmt0(),
                        r.altitudeGain.fmt0(),
                        r.vo2Max?.let { "%.1f".format(it) } ?: "–",
                        previous?.let { ChronoUnit.DAYS.between(date(it), date(r)).toString() } ?: "–",
                    ).joinToString(" | "),
                )
            }
        }.trimEnd()
    }

    /**
     * The saved training plan next to what was actually recorded, day by day, for the last
     * [weeks] weeks, followed by the planned sessions for the next two weeks.
     */
    fun planReview(
        plan: List<PlanWorkoutEntity>,
        runs: List<RunEntity>,
        weeks: Int,
        goal: String = "",
        today: LocalDate = LocalDate.now(),
    ): String {
        val dated = plan.mapNotNull { w -> w.localDate?.let { it to w } }.sortedBy { it.first }
        if (dated.isEmpty()) return "(no training plan saved)"
        val runsByDay = runs.groupBy { date(it) }
        val doneToday = runsByDay[today].orEmpty().isNotEmpty()
        // Today counts as past only once something was recorded; otherwise it is still upcoming.
        val lastPast = if (doneToday) today else today.minusDays(1)
        val from = maxOf(today.minusWeeks(weeks.toLong()), dated.first().first)
        val past = dated.filter { it.first in from..lastPast }
        val upcoming = dated.filter { it.first > lastPast && it.first <= today.plusDays(14) }

        return buildString {
            if (goal.isNotBlank()) appendLine("Training plan goal: ${goal.trim()}")
            if (from > lastPast) {
                appendLine("Plan vs actual: the saved plan starts on ${dated.first().first}, so there is nothing to compare yet.")
            } else {
                appendLine("Plan vs actual, $from to $lastPast (days with neither a planned session nor a recording are left out):")
                appendLine("date | planned | recorded")
                val planByDay = past.groupBy({ it.first }, { it.second })
                var day = from
                var matched = 0
                while (day <= lastPast) {
                    val planned = planByDay[day].orEmpty()
                    val done = runsByDay[day].orEmpty().sortedBy { it.startEpochMs }
                    if (planned.isNotEmpty() || done.isNotEmpty()) {
                        if (planned.any { it.workoutType != WorkoutType.CROSS } && done.isNotEmpty()) matched++
                        appendLine(
                            listOf(
                                dayLabel(day),
                                planned.joinToString("; ") { workout(it) }.ifEmpty { "nothing planned" },
                                done.joinToString("; ") { session(it) }.ifEmpty {
                                    if (planned.all { it.workoutType == WorkoutType.CROSS }) "not tracked (only runs are synced)" else "nothing recorded"
                                },
                            ).joinToString(" | "),
                        )
                    }
                    day = day.plusDays(1)
                }
                val plannedRuns = past.map { it.second }.filter { it.workoutType != WorkoutType.CROSS }
                val doneRuns = runs.filter { date(it) in from..lastPast }
                appendLine(
                    "Totals: sessions planned %d (%.1f km) · sessions recorded %d (%.1f km) · %d of %d planned days had a session recorded that day".format(
                        plannedRuns.size, plannedRuns.sumOf { (it.distanceKm ?: 0f).toDouble() },
                        doneRuns.size, doneRuns.sumOf { km(it) },
                        matched, plannedRuns.map { it.date }.distinct().size,
                    ),
                )
            }
            appendLine()
            if (upcoming.isEmpty()) appendLine("No sessions planned for the next 14 days.") else {
                appendLine("Planned next (through ${today.plusDays(14)}):")
                upcoming.forEach { (day, w) -> appendLine("${dayLabel(day)} | ${workout(w)}") }
            }
        }.trimEnd()
    }

    private fun workout(w: PlanWorkoutEntity): String = buildString {
        append(w.workoutType.label)
        if (w.title.isNotBlank()) append(": ${w.title.trim()}")
        val size = listOfNotNull(w.distanceKm?.let { "%.1f km".format(it) }, w.durationMin?.let { "$it min" })
        if (size.isNotEmpty()) append(", ${size.joinToString(" / ")}")
        if (w.target.isNotBlank()) append(", target ${w.target.trim()}")
    }

    private fun session(r: RunEntity): String = listOfNotNull(
        "${label(r)} %.2f km in %s".format(km(r), RunAnalysis.formatDuration(r.durationSec)),
        if (km(r) > 0) "${RunAnalysis.formatPace(r.durationSec / km(r))}/km" else null,
        r.meanHeartRate?.let { "avg HR %.0f".format(it) },
        r.maxHeartRate?.let { "max HR %.0f".format(it) },
    ).joinToString(", ")

    private fun dayLabel(d: LocalDate) = "$d ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun monday(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun date(r: RunEntity) = PromptRenderer.localStart(r).toLocalDate()
    private fun km(r: RunEntity) = (r.distanceM ?: 0f) / 1000.0
    private fun Float?.fmt0() = this?.let { "%.0f".format(it) } ?: "–"

    private fun weightedHr(list: List<RunEntity>): Double? {
        val l = list.filter { (it.meanHeartRate ?: 0f) > 0f }
        val t = l.sumOf { it.durationSec }
        return if (t == 0L) null else l.sumOf { it.meanHeartRate!!.toDouble() * it.durationSec } / t
    }

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
