package com.healthanalyzer.app.ai

import android.content.Context
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Kind of planned session; decides its colour in the calendar. */
enum class WorkoutType(val label: String, val short: String) {
    EASY("Easy run", "Easy"),
    LONG("Long run", "Long"),
    TEMPO("Tempo / threshold", "Tempo"),
    INTERVALS("Intervals / speed", "Intv"),
    RECOVERY("Recovery run", "Rec"),
    RACE("Race", "Race"),
    CROSS("Cross-training / strength", "Cross");

    companion object {
        /** Maps whatever the model wrote to a type; null for a rest day. */
        fun parse(raw: String?): WorkoutType? {
            val s = raw.orEmpty().lowercase()
            return when {
                "rest" in s || "off" == s.trim() -> null
                "race" in s -> RACE
                "long" in s -> LONG
                "tempo" in s || "threshold" in s || "steady" in s || "progress" in s -> TEMPO
                listOf("interval", "speed", "repeat", "fartlek", "hill", "track", "vo2").any { it in s } -> INTERVALS
                "recover" in s -> RECOVERY
                "cross" in s || "strength" in s || "bike" in s || "swim" in s || "gym" in s -> CROSS
                else -> EASY
            }
        }

        fun of(name: String): WorkoutType = entries.firstOrNull { it.name == name } ?: EASY
    }
}

/** One planned session on the training calendar. Days without a row are rest days. */
@Entity(tableName = "plan_workouts", indices = [Index("date")])
data class PlanWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local date, ISO (yyyy-MM-dd), so it sorts as text. */
    val date: String,
    /** A [WorkoutType] name. */
    val type: String,
    val title: String,
    val distanceKm: Float? = null,
    val durationMin: Int? = null,
    /** Target pace and/or heart rate, e.g. "6'10–6'30/km · HR < 145". */
    val target: String = "",
    /** How to run it: warm-up, repetitions, recoveries, why. */
    val notes: String = "",
    val createdEpochMs: Long = 0,
) {
    val workoutType: WorkoutType get() = WorkoutType.of(type)
    val localDate: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()
}

/** What the athlete asked for in the plan dialog. */
data class PlanRequest(
    val goal: String,
    /** Whole Monday–Sunday weeks to plan. */
    val weeks: Int,
    val runsPerWeek: Int,
    /** Also plan the days left in the current week, before the first whole week. */
    val fromToday: Boolean = false,
)

/** The current plan's request and the coach's summary, kept in the "ai_settings" preferences. */
data class PlanInfo(
    val goal: String = "",
    /** Length of the current plan in weeks. The dialog always opens at [DEFAULT_WEEKS]. */
    val weeks: Int = DEFAULT_WEEKS,
    val runsPerWeek: Int = 4,
    val summary: String = "",
    val createdEpochMs: Long = 0,
    /** "Provider · model" that wrote the plan. */
    val source: String = "",
) {
    companion object {
        private const val PREFS = "ai_settings"

        fun load(context: Context): PlanInfo {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return PlanInfo(
                goal = p.getString("planGoal", "").orEmpty(),
                weeks = p.getInt("planWeeks", DEFAULT_WEEKS),
                runsPerWeek = p.getInt("planRunsPerWeek", 4),
                summary = p.getString("planSummary", "").orEmpty(),
                createdEpochMs = p.getLong("planCreated", 0),
                source = p.getString("planSource", "").orEmpty(),
            )
        }

        fun save(context: Context, info: PlanInfo) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("planGoal", info.goal)
                .putInt("planWeeks", info.weeks)
                .putInt("planRunsPerWeek", info.runsPerWeek)
                .putString("planSummary", info.summary)
                .putLong("planCreated", info.createdEpochMs)
                .putString("planSource", info.source)
                .apply()
        }
    }
}

const val DEFAULT_WEEKS = 1

/** Settings for the "Today" card on the Plan tab, kept in the "ai_settings" preferences. */
data class RecapSettings(
    val enabled: Boolean = true,
    /** Resolved place for the weather; null = no weather. */
    val place: Place? = null,
) {
    companion object {
        private const val PREFS = "ai_settings"

        fun load(context: Context): RecapSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = p.getString("recapPlace", null)
            val place = if (name.isNullOrBlank()) null else
                Place(name, p.getFloat("recapLat", 0f).toDouble(), p.getFloat("recapLon", 0f).toDouble())
            return RecapSettings(p.getBoolean("recapEnabled", true), place)
        }

        fun save(context: Context, s: RecapSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("recapEnabled", s.enabled)
                .putString("recapPlace", s.place?.name)
                .putFloat("recapLat", s.place?.lat?.toFloat() ?: 0f)
                .putFloat("recapLon", s.place?.lon?.toFloat() ?: 0f)
                .apply()
        }
    }
}

/** Today's AI briefing, cached until the day, today's workouts or the place change. */
data class Recap(
    /** What it was written for; see [TodayRecap.key]. */
    val key: String = "",
    val text: String = "",
    val weatherLine: String = "",
    val error: String? = null,
    val createdEpochMs: Long = 0,
) {
    companion object {
        private const val PREFS = "ai_settings"

        fun load(context: Context): Recap {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Recap(
                key = p.getString("recapKey", "").orEmpty(),
                text = p.getString("recapText", "").orEmpty(),
                weatherLine = p.getString("recapWeather", "").orEmpty(),
                error = p.getString("recapError", null),
                createdEpochMs = p.getLong("recapCreated", 0),
            )
        }

        fun save(context: Context, r: Recap) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("recapKey", r.key)
                .putString("recapText", r.text)
                .putString("recapWeather", r.weatherLine)
                .putString("recapError", r.error)
                .putLong("recapCreated", r.createdEpochMs)
                .apply()
        }
    }
}

/** Prompt for the daily "Today" briefing on the Plan tab. Pure Kotlin. */
object TodayRecap {

    val SYSTEM = """
        You are the athlete's running coach writing a short briefing for today in the HealthAnalyzer
        app. Use only the plan, training and weather data given – never invent weather; if no
        forecast is given, say nothing about the weather. Be practical: when to run, how to dress,
        hydration, and whether to adjust pace or effort for heat, cold, wind or rain (or move the
        session indoors). If last night's sleep, the energy score or resting heart rate are clearly
        worse than the athlete's usual, say so and ease the session. If today is a rest day, say so
        and suggest how to recover.
        Format: simple Markdown, at most 5 short bullet points, under 110 words, no headings,
        no LaTeX. Reply in {{language}}.
    """.trimIndent()

    /** Changes whenever the briefing would need rewriting. */
    fun key(today: LocalDate, workouts: List<PlanWorkoutEntity>, place: Place?): String =
        listOf(today.toString(), place?.name.orEmpty(), workouts.joinToString(";") { "${it.id}:${it.title}:${it.distanceKm}:${it.target}" })
            .joinToString("|")

    fun userPrompt(
        today: LocalDate,
        workouts: List<PlanWorkoutEntity>,
        doneToday: String,
        weekPlannedKm: Double,
        weekDoneKm: Double,
        weather: String?,
    ): String = buildString {
        appendLine("Athlete:")
        appendLine("{{athlete}}")
        appendLine()
        appendLine("Today is $today (${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}).")
        appendLine()
        if (workouts.isEmpty()) {
            appendLine("Today's plan: rest day (nothing planned).")
        } else {
            appendLine("Today's plan:")
            workouts.forEach { w ->
                appendLine(
                    "- " + listOfNotNull(
                        w.title,
                        w.workoutType.label,
                        w.distanceKm?.let { "$it km" },
                        w.durationMin?.let { "$it min" },
                        w.target.takeIf { it.isNotBlank() }?.let { "target $it" },
                    ).joinToString(" · ") + (if (w.notes.isNotBlank()) "\n  ${w.notes}" else ""),
                )
            }
        }
        appendLine()
        appendLine("Already recorded today: ${doneToday.ifBlank { "nothing yet" }}")
        appendLine("This week (Mon–Sun): planned %.1f km, done %.1f km so far.".format(weekPlannedKm, weekDoneKm))
        appendLine()
        appendLine("{{recovery:7}}")
        appendLine()
        appendLine(weather ?: "No weather forecast available.")
        appendLine()
        append("Write today's briefing.")
    }
}

class PlanParseException(message: String) : Exception(message)

/** Prompt for a personal running plan and the parser for the model's JSON answer. Pure Kotlin. */
object TrainingPlan {

    val WEEK_OPTIONS = listOf(1, 2, 4, 6, 8, 12, 16)
    val RUNS_PER_WEEK_OPTIONS = (2..7).toList()

    val SYSTEM = """
        You are an experienced running coach and exercise physiologist. You write personal training
        plans from the athlete's own data: build on the volume and paces they actually run, increase
        weekly distance gradually (about 10% a week at most, with an easier week every 3–4 weeks),
        keep most running easy, never put two hard sessions on consecutive days, and taper before a
        goal race. Use the athlete's real heart-rate zones and recent paces for every target. If the
        goal is unrealistic for the time available, plan for the closest safe target and say so in
        the summary. Do not give medical diagnoses.
        Your whole reply must be one JSON object and nothing else – no Markdown fences, no text
        before or after it.
    """.trimIndent()

    /**
     * The dates a plan covers: [weeks] whole Monday–Sunday weeks, starting this Monday when today
     * is a Monday and next Monday otherwise. With [fromToday] the days left in the current week
     * are planned as well.
     */
    fun period(today: LocalDate, weeks: Int, fromToday: Boolean = false): Pair<LocalDate, LocalDate> {
        val firstMonday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
        return (if (fromToday) today else firstMonday) to firstMonday.plusWeeks(weeks.toLong()).minusDays(1)
    }

    /**
     * User message with {{placeholders}} for [PromptRenderer]. [kept] are sessions already on the
     * calendar between [today] and [start] that stay as they are.
     */
    fun userTemplate(
        request: PlanRequest,
        today: LocalDate,
        start: LocalDate,
        end: LocalDate,
        kept: List<PlanWorkoutEntity> = emptyList(),
    ): String = buildString {
        appendLine("Athlete:")
        appendLine("{{athlete}}")
        appendLine()
        appendLine("{{baseline}}")
        appendLine()
        appendLine("{{running_stats:12}}")
        appendLine()
        appendLine("{{weekly_summary:12}}")
        appendLine()
        appendLine("{{recent_runs:28}}")
        appendLine()
        appendLine("{{recovery:14}}")
        appendLine()
        appendLine("Today is $today (${weekday(today)}).")
        appendLine("My goal and constraints: ${request.goal.ifBlank { "No specific goal – improve general running fitness safely." }}")
        val firstMonday = period(today, request.weeks).first
        appendLine(
            "Write my running plan for ${request.weeks} whole week(s), Monday to Sunday, from $firstMonday to $end inclusive, " +
                "with about ${request.runsPerWeek} runs in each week.",
        )
        if (start.isBefore(firstMonday)) {
            appendLine(
                "Also plan the rest of the current week, $start to ${firstMonday.minusDays(1)}: add only as many runs there as fit " +
                    "with what I already ran this week (see the runs above), and if I already ran today, start tomorrow.",
            )
        } else if (today.isBefore(start)) {
            appendLine("Do not plan anything before $start. Take what I run until then into account.")
            if (kept.isNotEmpty()) {
                appendLine("Already on my calendar before $start (these stay as they are):")
                kept.forEach { appendLine("${weekday(LocalDate.parse(it.date))} ${it.date}: ${describe(it)}") }
            }
        }
        appendLine()
        appendLine("Dates in the plan period (use exactly these, the weekdays are correct):")
        append(dateList(start, end))
        appendLine()
        appendLine(JSON_SHAPE)
    }.trimEnd()

    val REVISE_SYSTEM = """
        You are the athlete's running coach. They already have a training plan and ask you to
        change it. Change only what the request needs and keep the rest of the plan as it is. Keep
        the plan safe: most running easy, no two hard sessions on consecutive days, no sudden jump
        in weekly distance; if a moved session would break that, adjust its neighbours and say so.
        If the athlete only asks a question, answer it and return the plan unchanged.
        Your whole reply must be one JSON object and nothing else – no Markdown fences, no text
        before or after it.
    """.trimIndent()

    /**
     * User message asking for a changed plan. [current] are the sessions planned in [start]..[end];
     * [earlier] are the athlete's earlier requests in this conversation, already applied.
     */
    fun reviseTemplate(
        request: String,
        today: LocalDate,
        start: LocalDate,
        end: LocalDate,
        current: List<PlanWorkoutEntity>,
        goal: String = "",
        earlier: List<String> = emptyList(),
    ): String = buildString {
        appendLine("Athlete:")
        appendLine("{{athlete}}")
        appendLine()
        appendLine("{{recent_runs:14}}")
        appendLine()
        appendLine("{{recovery:7}}")
        appendLine()
        appendLine("Today is $today (${weekday(today)}).")
        if (goal.isNotBlank()) appendLine("My goal and constraints for this plan: ${goal.trim()}")
        appendLine()
        appendLine("My current plan from $start to $end (days not listed are rest days):")
        appendLine(toJson(current))
        appendLine()
        if (earlier.isNotEmpty()) {
            appendLine("Changes I asked for earlier in this conversation (already in the plan above, keep them):")
            earlier.forEach { appendLine("- ${it.trim()}") }
            appendLine()
        }
        appendLine("What I want changed now: ${request.trim()}")
        appendLine()
        appendLine("Dates you may use (the weekdays are correct):")
        append(dateList(start, end))
        appendLine()
        appendLine(
            """
            Reply with one JSON object in exactly this shape:
            {
              "summary": "Under 80 words, written in {{language}}: what you changed and why, or the answer to my question.",
              "workouts": [ the complete plan from $start to $end after the change, every session in the same fields as above ]
            }
            Rules: return every session of the plan, changed or not, sorted by date; leave rest days
            out; copy unchanged sessions exactly; write title, target and notes in {{language}}.
            """.trimIndent(),
        )
    }.trimEnd()

    /** The plan in the same JSON fields the model is asked to reply with. */
    fun toJson(workouts: List<PlanWorkoutEntity>): String {
        val array = JsonArray()
        workouts.sortedBy { it.date }.forEach { w ->
            array.add(
                JsonObject().apply {
                    addProperty("date", w.date)
                    addProperty("weekday", w.localDate?.let(::weekday))
                    addProperty("type", w.workoutType.name.lowercase())
                    addProperty("title", w.title)
                    addProperty("distance_km", w.distanceKm)
                    addProperty("duration_min", w.durationMin)
                    addProperty("target", w.target)
                    addProperty("notes", w.notes)
                },
            )
        }
        return com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(array)
    }

    private fun describe(w: PlanWorkoutEntity) = listOfNotNull(
        w.title.ifBlank { w.workoutType.label },
        w.distanceKm?.let { "$it km" },
        w.target.takeIf { it.isNotBlank() },
    ).joinToString(", ")

    private fun dateList(start: LocalDate, end: LocalDate) = buildString {
        var d = start
        while (!d.isAfter(end)) {
            append(weekday(d)).append(' ').append(d)
            append(if (d.dayOfWeek == java.time.DayOfWeek.SUNDAY || d == end) "\n" else ", ")
            d = d.plusDays(1)
        }
    }

    private val JSON_SHAPE = """
        Reply with one JSON object in exactly this shape:
        {
          "summary": "Markdown, under 200 words: the plan's logic, weekly km progression, key sessions, and what to watch. Written in {{language}}.",
          "workouts": [
            {
              "date": "YYYY-MM-DD",
              "type": "easy | long | tempo | intervals | recovery | race | cross",
              "title": "short name, e.g. Easy 8 km",
              "distance_km": 8.0,
              "duration_min": 50,
              "target": "target pace and heart rate, e.g. 6'10\"–6'30\"/km · HR 135–145",
              "notes": "how to run it: warm-up, repetitions and recoveries, cool-down, purpose"
            }
          ]
        }
        Rules: one entry per training day, sorted by date; leave rest days out; distance_km is the
        session's total distance as a number (null for cross-training); write title, target and
        notes in {{language}}; keep each notes field under 60 words.
    """.trimIndent()

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    data class Parsed(val summary: String, val workouts: List<PlanWorkoutEntity>)

    /**
     * Reads the model's answer. Tolerates code fences and text around the JSON, a bare array
     * instead of the object, and string numbers. Entries with a bad date, outside [start]..[end]
     * or marked as rest are dropped.
     */
    fun parse(text: String, start: LocalDate, end: LocalDate, nowEpochMs: Long = 0): Parsed {
        val root = candidates(text).firstNotNullOfOrNull { c ->
            val e = runCatching { JsonParser.parseString(c) }.getOrNull()
            when {
                e == null -> null
                e.isJsonObject && workoutsArray(e.asJsonObject) != null -> e
                e.isJsonArray && e.asJsonArray.any { it.isJsonObject } -> e
                else -> null
            }
        } ?: throw PlanParseException(
            if (text.isBlank()) "The model returned an empty reply."
            else "The model's reply wasn't a complete plan. It may have been cut off – try fewer weeks, or raise the provider's max tokens.",
        )
        val array = if (root.isJsonArray) root.asJsonArray else workoutsArray(root.asJsonObject)!!
        val summary = if (root.isJsonObject) root.asJsonObject.str("summary").orEmpty().trim() else ""
        val workouts = array.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.mapNotNull { o ->
            val date = o.str("date")?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            if (date.isBefore(start) || date.isAfter(end)) return@mapNotNull null
            val type = WorkoutType.parse(o.str("type") ?: o.str("title")) ?: return@mapNotNull null
            val km = (o.num("distance_km") ?: o.num("distanceKm") ?: o.num("distance"))?.takeIf { it > 0 && it < 500 }
            PlanWorkoutEntity(
                date = date.toString(),
                type = type.name,
                title = o.str("title")?.trim().orEmpty().ifBlank { type.label },
                distanceKm = km?.toFloat(),
                durationMin = (o.num("duration_min") ?: o.num("durationMin") ?: o.num("duration"))
                    ?.takeIf { it > 0 && it < 1440 }?.let { Math.round(it).toInt() },
                target = o.str("target")?.trim().orEmpty(),
                notes = (o.str("notes") ?: o.str("description"))?.trim().orEmpty(),
                createdEpochMs = nowEpochMs,
            )
        }.sortedBy { it.date }
        if (workouts.isEmpty()) throw PlanParseException("The model's plan had no workouts between $start and $end.")
        return Parsed(summary, workouts)
    }

    /** The reply itself, then the outermost {...} and [...] found in it. */
    private fun candidates(text: String): List<String> {
        fun between(open: Char, close: Char): String? {
            val a = text.indexOf(open)
            val b = text.lastIndexOf(close)
            return if (a in 0 until b) text.substring(a, b + 1) else null
        }
        return listOfNotNull(text.trim().takeIf { it.isNotEmpty() }, between('{', '}'), between('[', ']'))
    }

    private fun workoutsArray(o: JsonObject): JsonArray? =
        listOf("workouts", "plan", "sessions").firstNotNullOfOrNull { k -> o.get(k)?.takeIf { it.isJsonArray }?.asJsonArray }

    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.num(name: String): Double? {
        val p = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return if (p.isNumber) p.asDouble else Regex("""\d+(?:[.,]\d+)?""").find(p.asString)?.value?.replace(',', '.')?.toDoubleOrNull()
    }

    /** Planned km per day summed over [from]..[to]. */
    fun plannedKm(workouts: List<PlanWorkoutEntity>, from: LocalDate, to: LocalDate): Double =
        workouts.filter { it.date >= from.toString() && it.date <= to.toString() }.sumOf { (it.distanceKm ?: 0f).toDouble() }
}
