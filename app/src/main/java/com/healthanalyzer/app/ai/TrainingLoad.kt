package com.healthanalyzer.app.ai

import com.healthanalyzer.app.data.DailyRecoveryEntity
import com.healthanalyzer.app.data.HrHistogram
import com.healthanalyzer.app.data.OtherWorkoutEntity
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
        others: List<OtherWorkoutEntity> = emptyList(),
    ): String {
        val start = monday(today).minusWeeks((weeks - 1).toLong())
        val byWeek = runs.groupBy { monday(date(it)) }
        val allOthers = others.filter { it.durationSec >= MIN_OTHER_SEC }
        // Recorded walks join the walk columns; "other" is strength, hiking, cycling and the like.
        val (otherWalks, shownOthers) = allOthers.partition { it.exerciseType == "WALKING" }
        val walksByWeek = otherWalks.groupBy { monday(date(it)) }
        val othersByWeek = shownOthers.groupBy { monday(date(it)) }
        val allOthersByWeek = allOthers.groupBy { monday(date(it)) }
        val otherZones = if (zones == null) emptyMap() else allOthers.mapNotNull { w -> zoneSeconds(w, zones)?.let { w.id to it } }.toMap()
        return buildString {
            appendLine("Weekly totals, last $weeks weeks (weeks start Monday; the last week is still in progress).")
            appendLine("Runs and walks are counted separately: $WALK_RULE.")
            if (otherWalks.isNotEmpty()) appendLine("Walks also include sessions recorded as walks (15 minutes or longer).")
            if (shownOthers.isNotEmpty()) appendLine("Other training is everything else recorded: strength, hiking, cycling and so on.")
            appendLine(
                "week of | runs | run km | run time | avg run pace/km | avg run HR | longest run km | walks | walk km | walk time" +
                    if (shownOthers.isNotEmpty()) " | other sessions | other time" else "",
            )
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
                        (walks.size + walksByWeek[week].orEmpty().size).toString(),
                        "%.1f".format(walks.sumOf { km(it) } + walksByWeek[week].orEmpty().sumOf { (it.distanceM ?: 0f) / 1000.0 }),
                        RunAnalysis.formatDuration(walks.sumOf { it.durationSec } + walksByWeek[week].orEmpty().sumOf { it.durationSec }),
                    ).plus(
                        if (shownOthers.isEmpty()) emptyList() else othersByWeek[week].orEmpty().let { o ->
                            listOf(o.size.toString(), RunAnalysis.formatDuration(o.sumOf { it.durationSec }))
                        },
                    ).joinToString(" | "),
                )
            }
            if (zones != null && (zoneSeconds.isNotEmpty() || otherZones.isNotEmpty())) {
                appendLine()
                appendLine("Weekly minutes in each heart-rate zone, ${if (otherZones.isEmpty()) "runs and walks together" else "all recorded training together"} (${zones.header.removePrefix("Time in HR zones ").trim('(', ')')}: ${zones.names.joinToString(" · ")}).")
                appendLine("Load = Z1 min × 1 + Z2 × 2 + Z3 × 3 + Z4 × 4 + Z5 × 5.")
                appendLine("week of | Z1 | Z2 | Z3 | Z4 | Z5 | load | load vs previous week | sessions with HR")
                var previous: Double? = null
                for (i in 0 until weeks) {
                    val week = start.plusWeeks(i.toLong())
                    val weekOthers = allOthersByWeek[week].orEmpty()
                    val sessions = byWeek[week].orEmpty().size + weekOthers.size
                    val withHr = byWeek[week].orEmpty().mapNotNull { zoneSeconds[it.id] } + weekOthers.mapNotNull { otherZones[it.id] }
                    val min = DoubleArray(5) { z -> withHr.sumOf { it[z] } / 60 }
                    val load = min.withIndex().sumOf { (z, m) -> m * (z + 1) }
                    val change = previous?.takeIf { it > 0 }?.let { "%+.0f%%".format((load - it) / it * 100) } ?: "–"
                    appendLine(
                        (listOf(week.toString()) + min.map { "%.0f".format(it) } +
                            listOf("%.0f".format(load), change, "${withHr.size} of $sessions")).joinToString(" | "),
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
        others: List<OtherWorkoutEntity> = emptyList(),
    ): String {
        val othersByDay = others.filter { it.durationSec >= MIN_OTHER_SEC }.groupBy { date(it) }
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
                    val other = othersByDay[day].orEmpty().sortedBy { it.startEpochMs }
                    if (planned.isNotEmpty() || done.isNotEmpty() || other.isNotEmpty()) {
                        if (planned.any { it.workoutType != WorkoutType.CROSS } && done.isNotEmpty()) matched++
                        appendLine(
                            listOf(
                                dayLabel(day),
                                planned.joinToString("; ") { workout(it) }.ifEmpty { "nothing planned" },
                                (done.map { session(it) } + other.map { session(it) }).joinToString("; ").ifEmpty { "nothing recorded" },
                            ).joinToString(" | "),
                        )
                    }
                    day = day.plusDays(1)
                }
                val plannedRuns = past.map { it.second }.filter { it.workoutType != WorkoutType.CROSS }
                val doneRuns = runs.filter { date(it) in from..lastPast }
                appendLine(
                    "Totals: runs planned %d (%.1f km) · runs and walks recorded %d (%.1f km) · %d of %d planned run days had one recorded that day".format(
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

    private fun session(w: OtherWorkoutEntity): String = listOfNotNull(
        "${otherLabel(w)} ${RunAnalysis.formatDuration(w.durationSec)}",
        w.distanceM?.takeIf { it >= 100 }?.let { "%.1f km".format(it / 1000) },
        w.meanHeartRate?.let { "avg HR %.0f".format(it) },
    ).joinToString(", ")

    /** Sessions shorter than this (mostly auto-detected strolls) are left out of the prompts. */
    private const val MIN_OTHER_SEC = 15 * 60L

    fun otherLabel(w: OtherWorkoutEntity): String {
        val title = w.title?.trim().orEmpty()
        return when {
            w.sourceAppId.orEmpty().contains("hevy", ignoreCase = true) -> "Strength training" + if (title.isNotEmpty()) " ($title)" else ""
            w.exerciseType == "OTHER" || w.exerciseType == "UNDEFINED" -> title.ifEmpty { "Other workout" }
            w.exerciseType == "WALKING" -> "Walk"
            w.exerciseType == "HIKING" -> "Hike"
            w.exerciseType == "BIKING" -> "Bike ride"
            else -> w.exerciseType.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    /** Seconds in each of the 5 zones from the workout's stored heart-rate histogram. */
    fun zoneSeconds(w: OtherWorkoutEntity, zones: HrZones.ZoneSet): DoubleArray? {
        val histogram = HrHistogram.decode(w.hrSeconds)
        val secs = DoubleArray(5)
        if (histogram.isEmpty()) {
            // No heart-rate rows (some apps write only the average): count the whole session at its average.
            val mean = w.meanHeartRate?.takeIf { it > 0 } ?: return null
            secs[zones.starts.count { mean >= it }] = w.durationSec.toDouble()
            return secs
        }
        histogram.forEach { (bpm, s) -> secs[zones.starts.count { bpm >= it }] += s }
        return secs
    }

    /** One line per non-running session since [since]; empty when there are none. */
    fun otherTraining(others: List<OtherWorkoutEntity>, days: Int, since: LocalDate): String {
        val shown = others.filter { it.durationSec >= MIN_OTHER_SEC && !date(it).isBefore(since) }.sortedBy { it.startEpochMs }
        if (shown.isEmpty()) return ""
        val (walks, rest) = shown.partition { it.exerciseType == "WALKING" }
        return buildString {
            appendLine("Other training in the last $days days (not runs; sessions under 15 minutes left out):")
            if (walks.isNotEmpty()) {
                val hr = walks.mapNotNull { it.meanHeartRate }
                appendLine(
                    "Walks: %d sessions, %s in total, %.1f km%s".format(
                        walks.size, RunAnalysis.formatDuration(walks.sumOf { it.durationSec }),
                        walks.sumOf { (it.distanceM ?: 0f) / 1000.0 },
                        if (hr.isNotEmpty()) ", avg HR %.0f".format(hr.average()) else "",
                    ),
                )
            }
            if (rest.isNotEmpty()) appendLine("date | type | time | km | avg HR | max HR | kcal")
            rest.forEach { w ->
                appendLine(
                    listOf(
                        localStart(w).format(DATE_TIME),
                        otherLabel(w),
                        RunAnalysis.formatDuration(w.durationSec),
                        w.distanceM?.takeIf { it >= 100 }?.let { "%.1f".format(it / 1000) } ?: "–",
                        w.meanHeartRate.fmt0(),
                        w.maxHeartRate.fmt0(),
                        w.calories?.takeIf { it > 0 }.fmt0(),
                    ).joinToString(" | "),
                )
            }
        }.trimEnd()
    }

    /**
     * Sleep, energy score and resting heart rate per day for the last [days] days, with the
     * last week set against the weeks before it. [rows] should reach back about 5 weeks.
     */
    fun recovery(rows: List<DailyRecoveryEntity>, days: Int, today: LocalDate = LocalDate.now()): String {
        val byDate = rows.mapNotNull { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { it to r } }.toMap()
        val from = today.minusDays(days - 1L)
        val shown = byDate.filterKeys { it in from..today }.toSortedMap()
        if (shown.isEmpty()) return "(no sleep or recovery data synced for the last $days days)"
        return buildString {
            appendLine("Recovery, last $days days (from Samsung Health; sleep is the night before that morning; – = not recorded):")
            appendLine("date | sleep score | time asleep (all sleeps) | deep | REM | awake | longest sleep, bedtime–wake | energy score | resting HR")
            shown.forEach { (day, r) ->
                appendLine(
                    listOf(
                        dayLabel(day),
                        r.sleepScore?.toString() ?: "–",
                        r.sleepMinutes.hm(),
                        r.deepMinutes.hm(),
                        r.remMinutes.hm(),
                        r.awakeMinutes.hm(),
                        if (r.bedtimeEpochMs != null && r.wakeEpochMs != null) "${clock(r.bedtimeEpochMs)}–${clock(r.wakeEpochMs)}" else "–",
                        r.energyScore?.let { "%.0f".format(it) } ?: "–",
                        r.restingHr?.toString() ?: "–",
                    ).joinToString(" | "),
                )
            }
            if (shown[today]?.restingHr != null) appendLine("(Today's resting HR is from the hours recorded so far.)")
            fun mean(range: ClosedRange<LocalDate>, f: (DailyRecoveryEntity) -> Double?): Pair<Double, Int>? =
                byDate.filterKeys { it in range }.values.mapNotNull(f).takeIf { it.size >= 3 }?.let { it.average() to it.size }
            val week = today.minusDays(6)..today
            val before = today.minusDays(34)..today.minusDays(7)
            val lines = listOf<Triple<String, String, (DailyRecoveryEntity) -> Double?>>(
                Triple("sleep score", "%.0f", { it.sleepScore?.toDouble() }),
                Triple("time asleep", "h", { it.sleepMinutes?.toDouble() }),
                Triple("energy score", "%.0f", { it.energyScore?.toDouble() }),
                Triple("resting HR", "%.0f bpm", { it.restingHr?.toDouble() }),
            ).mapNotNull { (name, fmt, f) ->
                val a = mean(week, f) ?: return@mapNotNull null
                val b = mean(before, f) ?: return@mapNotNull null
                fun show(v: Double) = if (fmt == "h") Math.round(v).toInt().hm() else fmt.format(v)
                "$name ${show(a.first)} (${a.second} days) vs ${show(b.first)} (${b.second} days)"
            }
            if (lines.isNotEmpty()) appendLine("Last 7 days vs the 4 weeks before: ${lines.joinToString(" · ")}")
        }.trimEnd()
    }

    /** A short line on how recovered the athlete was on [day], for a single run's analysis. */
    fun recoveryOn(day: LocalDate, rows: List<DailyRecoveryEntity>): String? {
        val r = rows.firstOrNull { it.date == day.toString() } ?: return null
        val parts = listOfNotNull(
            r.sleepMinutes?.let { "slept ${it.hm()}" + (r.sleepScore?.let { s -> " (sleep score $s)" } ?: "") },
            r.energyScore?.let { "energy score %.0f".format(it) },
            r.restingHr?.let { "resting HR $it bpm" },
        )
        if (parts.isEmpty()) return null
        val usual = rows.filter { it.date < day.toString() }
        val context = listOfNotNull(
            usual.mapNotNull { it.sleepScore }.takeIf { it.size >= 3 }?.let { "sleep score %.0f".format(it.average()) },
            usual.mapNotNull { it.energyScore }.takeIf { it.size >= 3 }?.let { "energy score %.0f".format(it.average()) },
            usual.mapNotNull { it.restingHr }.takeIf { it.size >= 3 }?.let { "resting HR %.0f bpm".format(it.average()) },
        )
        return "Recovery that day: ${parts.joinToString(", ")}" +
            if (context.isNotEmpty()) ". Average of the 4 weeks before: ${context.joinToString(", ")}" else ""
    }

    private fun Int?.hm() = this?.let { "%d:%02d".format(it / 60, it % 60) } ?: "–"
    private fun clock(epochMs: Long) = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
        .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    private fun localStart(w: OtherWorkoutEntity) = java.time.Instant.ofEpochMilli(w.startEpochMs)
        .atZone(w.zoneOffsetSec?.let { java.time.ZoneOffset.ofTotalSeconds(it) } ?: java.time.ZoneId.systemDefault()).toLocalDateTime()
    private fun date(w: OtherWorkoutEntity) = localStart(w).toLocalDate()

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
