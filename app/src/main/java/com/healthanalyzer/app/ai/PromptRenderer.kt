package com.healthanalyzer.app.ai

import android.content.Context
import com.healthanalyzer.app.data.RunAnalysis
import com.healthanalyzer.app.data.RunDao
import com.healthanalyzer.app.data.RunDetail
import com.healthanalyzer.app.data.RunEntity
import com.healthanalyzer.app.data.Sample
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Athlete details the prompts can use, stored in SharedPreferences. */
data class AthleteProfile(
    val maxHeartRate: Int? = null,
    val restingHeartRate: Int? = null,
    val notes: String = "",
    val language: String = "English",
    val birthDate: LocalDate? = null,
    val sex: String? = null,
    val heightCm: Float? = null,
    val weightKg: Float? = null,
    val bodyFatPercent: Float? = null,
    /** e.g. "Samsung Health, 2026-11-07" – where height/weight/etc. came from. */
    val importedFrom: String? = null,
) {
    val age: Int? get() = birthDate?.let { ChronoUnit.YEARS.between(it, LocalDate.now()).toInt() }

    companion object {
        private const val PREFS = "athlete_profile"

        fun load(context: Context): AthleteProfile {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            fun float(key: String) = p.getFloat(key, 0f).takeIf { it > 0f }
            return AthleteProfile(
                maxHeartRate = p.getInt("maxHr", 0).takeIf { it > 0 },
                restingHeartRate = p.getInt("restHr", 0).takeIf { it > 0 },
                notes = p.getString("notes", "").orEmpty(),
                language = p.getString("language", "English").orEmpty().ifBlank { "English" },
                birthDate = p.getString("birthDate", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                sex = p.getString("sex", null),
                heightCm = float("heightCm"),
                weightKg = float("weightKg"),
                bodyFatPercent = float("bodyFat"),
                importedFrom = p.getString("importedFrom", null),
            )
        }

        fun save(context: Context, profile: AthleteProfile) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("maxHr", profile.maxHeartRate ?: 0)
                .putInt("restHr", profile.restingHeartRate ?: 0)
                .putString("notes", profile.notes)
                .putString("language", profile.language)
                .putString("birthDate", profile.birthDate?.toString())
                .putString("sex", profile.sex)
                .putFloat("heightCm", profile.heightCm ?: 0f)
                .putFloat("weightKg", profile.weightKg ?: 0f)
                .putFloat("bodyFat", profile.bodyFatPercent ?: 0f)
                .putString("importedFrom", profile.importedFrom)
                .apply()
        }
    }
}

/** An AI-written summary of the athlete's current fitness, reused as context for later analyses. */
data class Baseline(
    val text: String,
    val createdEpochMs: Long,
    val source: String,
) {
    companion object {
        private const val PREFS = "athlete_baseline"

        fun load(context: Context): Baseline? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val text = p.getString("text", null)?.takeIf { it.isNotBlank() } ?: return null
            return Baseline(text, p.getLong("created", 0), p.getString("source", "").orEmpty())
        }

        fun save(context: Context, baseline: Baseline?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (baseline == null) remove("text").remove("created").remove("source")
                else putString("text", baseline.text).putLong("created", baseline.createdEpochMs).putString("source", baseline.source)
            }.apply()
        }

        /** Whether analyses should include the saved baseline automatically. */
        fun useInAnalyses(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("use", true)

        fun setUseInAnalyses(context: Context, use: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("use", use).apply()
    }

    fun header(): String =
        "Athlete baseline (written ${Instant.ofEpochMilli(createdEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()}" +
            (if (source.isNotBlank()) " by $source" else "") + ")"
}

/**
 * Fills {{placeholders}} in prompt templates with text built from the local run database.
 *
 * Placeholders:
 *  {{athlete}}            profile: max/resting HR, notes, goals
 *  {{language}}           reply language from the profile
 *  {{today}}              today's date
 *  {{run}}                the selected run in depth: metrics, km splits, HR zones, drift
 *  {{recent_runs}}        one line per run in the last 28 days ({{recent_runs:N}} for N days)
 *  {{weekly_summary}}     weekly totals for the last 12 weeks ({{weekly_summary:N}} for N weeks)
 */
class PromptRenderer(private val context: Context, private val dao: RunDao) {

    suspend fun render(template: String, run: RunDetail?): String {
        prepare(template)
        val profile = AthleteProfile.load(context)
        val maxHr = profile.maxHeartRate ?: dao.highestHeartRate()?.roundToInt()
        var out = template
        out = out.replace("{{athlete}}", athlete(profile, maxHr))
        out = out.replace("{{language}}", profile.language)
        if ("{{baseline}}" in out) {
            out = out.replace("{{baseline}}", Baseline.load(context)?.let { "${it.header()}:\n${it.text}" } ?: "(no baseline saved yet)")
        }
        out = STATS.replace(out) { m -> statsCache[m.groupValues[1].toIntOrNull() ?: 12].orEmpty() }
        out = out.replace("{{today}}", LocalDate.now().toString())
        if ("{{run}}" in out) {
            out = out.replace("{{run}}", run?.let { runDetail(it, maxHr) } ?: "(no run selected)")
        }
        out = RECENT.replace(out) { m ->
            val days = m.groupValues[1].toIntOrNull() ?: 28
            recentRunsBlocking(days)
        }
        out = DYNAMICS.replace(out) { m -> dynamicsCache[m.groupValues[1].toIntOrNull() ?: 90].orEmpty() }
        out = WEEKLY.replace(out) { m ->
            val weeks = m.groupValues[1].toIntOrNull() ?: 12
            weeklyBlocking(weeks)
        }
        return out
    }

    // Regex.replace takes a non-suspending lambda, so results are pre-fetched in render().
    private var recentCache: Map<Int, String> = emptyMap()
    private var weeklyCache: Map<Int, String> = emptyMap()
    private var statsCache: Map<Int, String> = emptyMap()
    private var dynamicsCache: Map<Int, String> = emptyMap()
    private fun recentRunsBlocking(days: Int) = recentCache[days].orEmpty()
    private fun weeklyBlocking(weeks: Int) = weeklyCache[weeks].orEmpty()

    private suspend fun prepare(template: String) {
        recentCache = RECENT.findAll(template).map { it.groupValues[1].toIntOrNull() ?: 28 }.distinct()
            .associateWith { recentRuns(it) }
        weeklyCache = WEEKLY.findAll(template).map { it.groupValues[1].toIntOrNull() ?: 12 }.distinct()
            .associateWith { weekly(it) }
        dynamicsCache = DYNAMICS.findAll(template).map { it.groupValues[1].toIntOrNull() ?: 90 }.distinct()
            .associateWith { dynamics(it) }
        if (STATS.containsMatchIn(template)) {
            val profile = AthleteProfile.load(context)
            val maxHr = profile.maxHeartRate ?: dao.highestHeartRate()?.roundToInt()
            statsCache = STATS.findAll(template).map { it.groupValues[1].toIntOrNull() ?: 12 }.distinct()
                .associateWith { RunningStats.build(dao.runsSince(monthsAgo(it)), it, maxHr) }
        }
    }

    private fun monthsAgo(months: Int) = LocalDate.now().minusMonths(months.toLong())
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun athlete(p: AthleteProfile, maxHr: Int?): String = buildString {
        val body = listOfNotNull(
            p.age?.let { "age $it" },
            p.sex,
            p.heightCm?.let { "%.0f cm".format(it) },
            p.weightKg?.let { "%.1f kg".format(it) },
            if (p.heightCm != null && p.weightKg != null) "BMI %.1f".format(p.weightKg / (p.heightCm / 100) / (p.heightCm / 100)) else null,
            p.bodyFatPercent?.let { "body fat %.1f%%".format(it) },
        )
        if (body.isNotEmpty()) appendLine(body.joinToString(", ").replaceFirstChar { it.uppercase() })
        append("Max heart rate: ")
        append(
            when {
                p.maxHeartRate != null -> "${p.maxHeartRate} bpm (set by athlete)"
                maxHr != null -> "$maxHr bpm (highest recorded in synced runs; true max may be higher)"
                else -> "unknown"
            },
        )
        p.restingHeartRate?.let { append("\nResting heart rate: $it bpm") }
        if (p.notes.isNotBlank()) append("\nNotes / goals: ${p.notes.trim()}")
    }

    private suspend fun recentRuns(days: Int): String {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()
        val runs = dao.runsSince(since)
        if (runs.isEmpty()) return "(no runs in the last $days days)"
        return buildString {
            appendLine("Runs in the last $days days (${runs.size}):")
            appendLine("date | type | km | time | pace/km | avg HR | max HR | cadence | elev+ m | VO2max")
            runs.forEach { r ->
                appendLine(
                    listOf(
                        localStart(r).format(DATE_TIME),
                        typeLabel(r.exerciseType),
                        km(r),
                        RunAnalysis.formatDuration(r.durationSec),
                        RunAnalysis.formatPace(pace(r)),
                        r.meanHeartRate.fmt0(),
                        r.maxHeartRate.fmt0(),
                        r.meanCadence.fmt0(),
                        r.altitudeGain.fmt0(),
                        r.vo2Max?.let { "%.1f".format(it) } ?: "–",
                    ).joinToString(" | "),
                )
            }
        }.trimEnd()
    }

    /**
     * Running-form averages per run type over the last [days] days, for shoe and technique
     * questions. Each metric is averaged only over runs that recorded it, and says how many did.
     */
    private suspend fun dynamics(days: Int): String {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()
        val runs = dao.runsSince(since)
        if (runs.isEmpty()) return "(no runs in the last $days days)"
        fun avg(list: List<RunEntity>, f: (RunEntity) -> Float?): Pair<Double, Int>? {
            val v = list.mapNotNull(f)
            return if (v.isEmpty()) null else v.average() to v.size
        }
        return buildString {
            appendLine("Running form, last $days days (averages per run; n = runs with that metric):")
            runs.groupBy { typeLabel(it.exerciseType) }.forEach { (type, list) ->
                val dist = list.sumOf { (it.distanceM ?: 0f).toDouble() } / 1000
                val time = list.sumOf { it.durationSec }
                appendLine("$type: ${list.size} runs, ${"%.0f".format(dist)} km, avg pace ${if (dist > 0) RunAnalysis.formatPace(time / dist) else "–"}/km")
                listOf(
                    Triple("cadence", "%.0f spm", avg(list) { it.meanCadence }),
                    Triple("step length", "%.2f m", avg(list) { it.stepLengthMm?.div(1000) }),
                    Triple("ground contact time", "%.0f ms", avg(list) { it.groundContactMs }),
                    Triple("flight time", "%.0f ms", avg(list) { it.flightTimeMs }),
                    Triple("vertical oscillation", "%.1f cm", avg(list) { it.verticalOscillationMm?.div(10) }),
                    Triple("vertical ratio", "%.1f%%", avg(list) { it.verticalRatioPct }),
                    Triple("GCT balance (left)", "%.1f%%", avg(list) { it.groundContactBalancePct }),
                    Triple("left/right asymmetry", "%.1f%%", avg(list) { it.asymmetryPct }),
                    Triple("stride regularity", "%.0f%%", avg(list) { it.regularityPct }),
                    Triple("leg stiffness", "%.1f kN/m", avg(list) { it.stiffnessKnM }),
                    Triple("elevation gain per run", "%.0f m", avg(list) { it.altitudeGain }),
                ).forEach { (label, fmt, v) ->
                    if (v != null) appendLine("  $label: ${fmt.format(v.first)} (n=${v.second})")
                }
            }
        }.trimEnd()
    }

    private suspend fun weekly(weeks: Int): String {
        val start = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .minusWeeks((weeks - 1).toLong())
        val runs = dao.runsSince(start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val byWeek = runs.groupBy { localStart(it).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        return buildString {
            appendLine("Weekly totals, last $weeks weeks (weeks start Monday):")
            appendLine("week of | runs | km | time | avg pace/km | avg HR | longest km")
            for (i in 0 until weeks) {
                val week = start.plusWeeks(i.toLong())
                val list = byWeek[week].orEmpty()
                val dist = list.sumOf { (it.distanceM ?: 0f).toDouble() }
                val time = list.sumOf { it.durationSec }
                val hrList = list.mapNotNull { r -> r.meanHeartRate?.let { it to r.durationSec } }
                val avgHr = if (hrList.isEmpty()) null else
                    hrList.sumOf { (hr, t) -> hr.toDouble() * t } / hrList.sumOf { it.second }.coerceAtLeast(1)
                appendLine(
                    listOf(
                        week.toString(),
                        list.size.toString(),
                        "%.1f".format(dist / 1000),
                        RunAnalysis.formatDuration(time),
                        if (dist > 0) RunAnalysis.formatPace(time / dist * 1000) else "–",
                        avgHr?.let { "%.0f".format(it) } ?: "–",
                        list.maxOfOrNull { (it.distanceM ?: 0f) / 1000 }?.let { "%.1f".format(it) } ?: "–",
                    ).joinToString(" | "),
                )
            }
        }.trimEnd()
    }

    private fun runDetail(d: RunDetail, maxHr: Int?): String {
        val r = d.run
        return buildString {
            appendLine("${typeLabel(r.exerciseType)} on ${localStart(r).format(DATE_TIME)}${r.title?.let { " – $it" } ?: ""}")
            appendLine("Distance: ${km(r)} km · Moving time: ${RunAnalysis.formatDuration(r.durationSec)} · Avg pace: ${RunAnalysis.formatPace(pace(r))}/km")
            appendLine("Elapsed (incl. pauses): ${RunAnalysis.formatDuration((r.endEpochMs - r.startEpochMs) / 1000)}")
            appendLine("Heart rate avg/max/min: ${r.meanHeartRate.fmt0()}/${r.maxHeartRate.fmt0()}/${r.minHeartRate.fmt0()} bpm")
            appendLine("Cadence avg/max: ${r.meanCadence.fmt0()}/${r.maxCadence.fmt0()} spm · Steps: ${r.stepCount ?: "–"}")
            appendLine("Speed max: ${r.maxSpeed?.let { "%.1f km/h".format(it * 3.6f) } ?: "–"}")
            appendLine("Elevation gain: ${r.altitudeGain.fmt0()} m · Altitude range: ${r.minAltitude.fmt0()}–${r.maxAltitude.fmt0()} m")
            appendLine("Calories: ${"%.0f".format(r.calories)} kcal · VO2max estimate: ${r.vo2Max?.let { "%.1f".format(it) } ?: "–"}")
            if (r.meanPower != null) appendLine("Power avg/max: ${r.meanPower.fmt0()}/${r.maxPower.fmt0()} W")
            val dynamics = listOfNotNull(
                r.groundContactMs?.let { "ground contact %.0f ms".format(it) },
                r.groundContactBalancePct?.let { "GCT balance %.1f%% left".format(it) },
                r.verticalOscillationMm?.let { "vertical oscillation %.1f cm".format(it / 10) },
                r.verticalRatioPct?.let { "vertical ratio %.1f%%".format(it) },
                r.stepLengthMm?.let { "step length %.2f m".format(it / 1000) },
                r.flightTimeMs?.let { "flight time %.0f ms".format(it) },
                r.asymmetryPct?.let { "left/right asymmetry %.1f%%".format(it) },
                r.regularityPct?.let { "stride regularity %.0f%%".format(it) },
                r.stiffnessKnM?.let { "leg stiffness %.1f kN/m".format(it) },
            )
            if (dynamics.isNotEmpty()) appendLine("Running dynamics: ${dynamics.joinToString(", ")}")
            if (r.aerobicTrainingEffect != null || r.anaerobicTrainingEffect != null) {
                appendLine("Garmin training effect: aerobic ${r.aerobicTrainingEffect ?: "–"}, anaerobic ${r.anaerobicTrainingEffect ?: "–"}")
            }
            r.sweatLossMl?.let { appendLine("Estimated sweat loss: %.0f ml".format(it)) }
            appendLine("Data source: ${if (r.sourceOrDefault == com.healthanalyzer.app.data.SOURCE_GARMIN_FIT) "Garmin (FIT file)" else "Samsung Health"}")

            val splits = RunAnalysis.splits(d.samples, r.distanceM)
            if (splits.isNotEmpty()) {
                appendLine()
                appendLine("Km splits (km | pace | avg HR | cadence):")
                splits.forEach { s ->
                    val label = if (s.distanceM >= 999) "${s.index}" else "${s.index} (%.2f km)".format(s.distanceM / 1000)
                    appendLine("$label | ${RunAnalysis.formatPace(s.paceSecPerKm)} | ${s.meanHeartRate.fmt0()} | ${s.meanCadence.fmt0()}")
                }
            }
            if (maxHr != null) {
                zones(d.samples, maxHr)?.let { appendLine(); appendLine(it) }
            }
            drift(d.samples)?.let { appendLine(); appendLine(it) }
        }.trimEnd()
    }

    /** Time in 5 zones by % of max HR, from the heart-rate rows. */
    private fun zones(samples: List<Sample>, maxHr: Int): String? {
        val hr = samples.filter { (it.hr ?: 0f) > 0 }.sortedBy { it.t }
        if (hr.size < 2) return null
        val secs = DoubleArray(5)
        for (i in 1 until hr.size) {
            val dt = (hr[i].t - hr[i - 1].t) / 1000.0
            if (dt <= 0 || dt > 30) continue
            val pct = hr[i].hr!! / maxHr
            val z = when {
                pct < 0.6 -> 0
                pct < 0.7 -> 1
                pct < 0.8 -> 2
                pct < 0.9 -> 3
                else -> 4
            }
            secs[z] += dt
        }
        val total = secs.sum().takeIf { it > 0 } ?: return null
        val names = listOf("Z1 <60%", "Z2 60–70%", "Z3 70–80%", "Z4 80–90%", "Z5 ≥90%")
        return "Time in HR zones (% of max HR $maxHr): " + names.indices.joinToString(" · ") { i ->
            "${names[i]} ${RunAnalysis.formatDuration(secs[i].roundToInt().toLong())} (${(secs[i] / total * 100).roundToInt()}%)"
        }
    }

    /** Aerobic decoupling: change in speed-per-heartbeat between the first and second half. */
    private fun drift(samples: List<Sample>): String? {
        val sorted = samples.sortedBy { it.t }
        if (sorted.size < 20) return null
        val mid = (sorted.first().t + sorted.last().t) / 2
        fun ef(part: List<Sample>): Pair<Double, Double>? {
            val sp = part.mapNotNull { it.speed?.toDouble() }.filter { it > 0.5 }
            val hr = part.mapNotNull { it.hr?.toDouble() }.filter { it > 0 }
            if (sp.isEmpty() || hr.isEmpty()) return null
            return sp.average() to hr.average()
        }
        val a = ef(sorted.filter { it.t < mid }) ?: return null
        val b = ef(sorted.filter { it.t >= mid }) ?: return null
        val ef1 = a.first / a.second
        val ef2 = b.first / b.second
        val decoupling = (ef1 - ef2) / ef1 * 100
        return "First half: ${RunAnalysis.formatPace(1000 / a.first)}/km at ${a.second.roundToInt()} bpm · " +
            "Second half: ${RunAnalysis.formatPace(1000 / b.first)}/km at ${b.second.roundToInt()} bpm · " +
            "Pace:HR decoupling ${"%.1f".format(decoupling)}%"
    }

    companion object {
        private val RECENT = Regex("""\{\{recent_runs(?::(\d+))?\}\}""")
        private val WEEKLY = Regex("""\{\{weekly_summary(?::(\d+))?\}\}""")
        private val STATS = Regex("""\{\{running_stats(?::(\d+))?\}\}""")
        private val DYNAMICS = Regex("""\{\{dynamics(?::(\d+))?\}\}""")
        private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val PLACEHOLDER_HELP = """
            {{athlete}} – max/resting HR, notes and goals from Settings
            {{language}} – reply language from Settings
            {{today}} – today's date
            {{run}} – the selected run: metrics, km splits, HR zones, pace:HR drift
            {{recent_runs}} – one line per run, last 28 days ({{recent_runs:56}} = 56 days)
            {{weekly_summary}} – weekly totals, last 12 weeks ({{weekly_summary:24}} = 24 weeks)
            {{dynamics}} – running form averages (cadence, contact time, oscillation, asymmetry…), last 90 days ({{dynamics:180}} = 180 days)
            {{running_stats}} – long-term stats, last 12 months: monthly totals, bests, easy-run pace/HR ({{running_stats:24}} = 24 months)
            {{baseline}} – your saved athlete baseline (also added automatically when "Use baseline" is on)
        """.trimIndent()

        fun localStart(r: RunEntity) = Instant.ofEpochMilli(r.startEpochMs)
            .atZone(r.zoneOffsetSec?.let { ZoneOffset.ofTotalSeconds(it) } ?: ZoneId.systemDefault())
            .toLocalDateTime()

        fun runLabel(r: RunEntity) = "${localStart(r).format(DATE_TIME)} · ${typeLabel(r.exerciseType)} · ${km(r)} km"

        fun typeLabel(type: String) = when (type) {
            "RUNNING" -> "Outdoor run"
            "TREADMILL" -> "Treadmill"
            "TRACK_RUNNING" -> "Track run"
            else -> type
        }

        private fun km(r: RunEntity) = "%.2f".format((r.distanceM ?: 0f) / 1000)

        private fun pace(r: RunEntity): Double {
            val d = r.distanceM ?: return 0.0
            return if (d > 0) r.durationSec / d.toDouble() * 1000 else 0.0
        }

        private fun Float?.fmt0() = this?.let { "%.0f".format(it) } ?: "–"
    }
}

/** Builds the athlete baseline; its answer is saved and reused by later analyses. */
val BASELINE_PROMPT = PromptTemplateEntity(
    name = "Build athlete baseline",
    savesBaseline = true,
    systemPrompt = """
        You are an experienced running coach and exercise physiologist. From the data given, write a
        compact athlete baseline that will be pasted into future analyses as background. Use only the
        data provided, give numbers with units, and say when an estimate is uncertain.
        Reply in {{language}}.
    """.trimIndent(),
    userTemplate = """
        Athlete:
        {{athlete}}

        Today is {{today}}.

        {{running_stats:12}}

        {{weekly_summary:12}}

        Previous baseline, if any (update it rather than start over):
        {{baseline}}

        Write the baseline with these headings, in under 350 words:
        1. Current fitness level (one paragraph)
        2. Estimated heart-rate zones and paces (table: zone, HR range, pace range)
        3. Typical easy pace and HR; estimated threshold pace and HR; VO2max trend
        4. Sustainable weekly volume and long-run distance right now
        5. Strengths and limiters
        6. Things to watch (load jumps, gaps, data quality)
    """.trimIndent(),
)

/** Running-shoe advice from training volume, paces, surfaces and running form. */
val SHOE_PROMPTS = listOf(
    PromptTemplateEntity(
        name = "Running shoe recommendation",
        systemPrompt = """
            You are an experienced running coach and running-footwear specialist. Recommend shoes from
            the training data given: volume, pace ranges, surfaces (road, treadmill, trail, hills),
            body weight and running form. Explain every recommendation with the numbers that drive it.
            Name shoe categories first, then 2–3 well-known current models per role as examples, and
            say that fit and comfort in the store matter more than any spec. You cannot see the
            athlete's feet or gait video – say what would need an in-person gait check. Do not
            diagnose injuries. Reply in {{language}}.
        """.trimIndent(),
        userTemplate = """
            Athlete:
            {{athlete}}

            Today is {{today}}.

            {{running_stats:12}}

            {{weekly_summary:12}}

            {{dynamics:90}}

            {{recent_runs:28}}

            Recommend a running shoe rotation for me:
            1. My runner profile as it matters for shoes: weekly km, easy / tempo / long-run paces,
               surfaces, body weight, cadence, ground contact, vertical oscillation, asymmetry.
            2. A table: shoe role (daily trainer, easy/recovery, tempo/speed, long run, race, plus
               treadmill or trail if my data shows them) | key specs (cushioning/stack, drop, weight,
               plate or not, neutral vs stability) | example models | why, from my data.
            3. How many pairs make sense at my volume, and roughly when to replace each (km per pair
               and how many weeks that is for me).
            4. Anything in my form data (e.g. high asymmetry, long contact time, low cadence) that
               should change the choice or be checked by a physio or gait analysis.
        """.trimIndent(),
    ),
    PromptTemplateEntity(
        name = "Race-day shoe pick",
        systemPrompt = """
            You are an experienced running coach and running-footwear specialist. Pick race-day shoes
            from the athlete's data and goal race. Weigh speed benefit against stability, durability
            and the athlete's experience with plated shoes. Give examples of well-known current
            models, and advise how to break them in before race day. Reply in {{language}}.
        """.trimIndent(),
        userTemplate = """
            Athlete:
            {{athlete}}

            Today is {{today}}.

            {{running_stats:12}}

            {{dynamics:90}}

            {{recent_runs:56}}

            Goal race: use the goal in my notes above; if there is none, assume the distance my
            training points to and say which you assumed.

            Tell me:
            1. My realistic race pace for that distance from my recent training.
            2. Whether a carbon-plated super shoe, a plated or unplated lightweight trainer, or my
               daily trainer suits me best for it – and why, using my pace, weight, cadence and form.
            3. 2–3 example models for that choice, with drop and stack height.
            4. A break-in plan: which workouts and long runs to test them in before race day.
        """.trimIndent(),
    ),
)

/** Prompts created on first launch; all of them can be edited or deleted. */
val DEFAULT_PROMPTS = listOf(
    BASELINE_PROMPT,
    PromptTemplateEntity(
        name = "Analyze this run",
        systemPrompt = """
            You are an experienced running coach and exercise physiologist. You analyze data from a
            Samsung Galaxy Watch synced through Samsung Health. Be specific and use the numbers given.
            Say clearly when the data cannot support a conclusion. Do not give medical diagnoses.
            Reply in {{language}}.
        """.trimIndent(),
        userTemplate = """
            Athlete:
            {{athlete}}

            Today is {{today}}. Analyze this run:
            {{run}}

            For context, my recent training:
            {{recent_runs:28}}

            Cover: pacing and splits, heart-rate response and drift, cadence, how this run fits my
            recent load, and 2–3 concrete suggestions for upcoming runs.
        """.trimIndent(),
    ),
    PromptTemplateEntity(
        name = "Training review (last 4 weeks)",
        systemPrompt = """
            You are an experienced running coach. Review training load and progression from the data
            given. Be concrete, flag injury risk from sudden load jumps, and keep the plan realistic.
            Reply in {{language}}.
        """.trimIndent(),
        userTemplate = """
            Athlete:
            {{athlete}}

            Today is {{today}}.

            {{weekly_summary:12}}

            {{recent_runs:28}}

            Review my last 4 weeks against the 12-week trend: volume, intensity distribution
            (easy vs hard by heart rate), consistency, long-run progression, VO2max trend. Then
            propose a plan for the next 7 days with distances and target heart rates or paces.
        """.trimIndent(),
    ),
) + SHOE_PROMPTS
