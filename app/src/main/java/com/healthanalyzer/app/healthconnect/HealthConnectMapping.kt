package com.healthanalyzer.app.healthconnect

import com.healthanalyzer.app.data.Sample
import java.time.Instant

/** One Health Connect sleep session, reduced to what the recovery summary needs. */
data class SleepSpan(
    val start: Instant,
    val end: Instant,
    val stages: List<SleepStage> = emptyList(),
)

data class SleepStage(val start: Instant, val end: Instant, val type: Int)

/** Pure-JVM parts of the Health Connect source, kept apart so they can be unit-tested. */
object HealthConnectMapping {

    // ExerciseSessionRecord.EXERCISE_TYPE_* values.
    const val TYPE_RUNNING = 56
    const val TYPE_RUNNING_TREADMILL = 57

    // SleepSessionRecord.STAGE_TYPE_* values.
    const val STAGE_AWAKE = 1
    const val STAGE_SLEEPING = 2
    const val STAGE_OUT_OF_BED = 3
    const val STAGE_LIGHT = 4
    const val STAGE_DEEP = 5
    const val STAGE_REM = 6
    const val STAGE_AWAKE_IN_BED = 7

    private val ASLEEP = setOf(STAGE_SLEEPING, STAGE_LIGHT, STAGE_DEEP, STAGE_REM)
    private val AWAKE = setOf(STAGE_AWAKE, STAGE_AWAKE_IN_BED)

    fun isRun(exerciseType: Int) = exerciseType == TYPE_RUNNING || exerciseType == TYPE_RUNNING_TREADMILL

    /**
     * The name the rest of the app uses for an exercise type. These are Samsung's names, so
     * Health Connect workouts are labelled and grouped like the ones read from Samsung Health.
     */
    fun typeName(exerciseType: Int): String = when (exerciseType) {
        TYPE_RUNNING -> "RUNNING"
        TYPE_RUNNING_TREADMILL -> "TREADMILL"
        8 -> "BIKING"
        9 -> "STATIONARY_BIKE"
        13 -> "CALISTHENICS"
        16 -> "DANCING"
        25 -> "ELLIPTICAL"
        36 -> "HIGH_INTENSITY_INTERVAL_TRAINING"
        37 -> "HIKING"
        48 -> "PILATES"
        51 -> "ROCK_CLIMBING"
        53 -> "ROWING"
        54 -> "ROWING_MACHINE"
        61 -> "SKIING"
        68, 69 -> "STAIR_CLIMBING"
        70 -> "STRENGTH_TRAINING"
        71 -> "STRETCHING"
        73, 74 -> "SWIMMING"
        79 -> "WALKING"
        81 -> "WEIGHTLIFTING"
        83 -> "YOGA"
        else -> "OTHER"
    }

    /**
     * Joins the separate heart-rate, speed, cadence and power series into one list of samples,
     * one per timestamp, oldest first. Each series is (epoch ms, value).
     */
    fun mergeSeries(
        hr: List<Pair<Long, Float>>,
        speed: List<Pair<Long, Float>>,
        cadence: List<Pair<Long, Float>>,
        power: List<Pair<Long, Float>>,
    ): List<Sample> {
        val byTime = sortedMapOf<Long, Sample>()
        fun put(series: List<Pair<Long, Float>>, set: (Sample, Float) -> Sample) = series.forEach { (t, v) ->
            byTime[t] = set(byTime[t] ?: Sample(t, null, null, null, null), v)
        }
        put(hr) { s, v -> s.copy(hr = v) }
        put(speed) { s, v -> s.copy(speed = v) }
        put(cadence) { s, v -> s.copy(cadence = v) }
        put(power) { s, v -> s.copy(power = v) }
        return byTime.values.toList()
    }

    /** Minutes asleep: from the stages when the session has them, otherwise its whole length. */
    fun asleepMinutes(sleep: SleepSpan): Int =
        if (sleep.stages.isEmpty()) minutes(sleep.start, sleep.end) else stageMinutes(sleep, ASLEEP)

    fun deepMinutes(sleep: SleepSpan): Int? = stageMinutesOrNull(sleep, setOf(STAGE_DEEP))

    fun remMinutes(sleep: SleepSpan): Int? = stageMinutesOrNull(sleep, setOf(STAGE_REM))

    fun awakeMinutes(sleep: SleepSpan): Int? = stageMinutesOrNull(sleep, AWAKE)

    /**
     * Several apps can write the same night to Health Connect. Keeps the longest sleeps and drops
     * any that overlap one already kept, so a night is not counted twice while naps still count.
     */
    fun withoutOverlaps(sleeps: List<SleepSpan>): List<SleepSpan> {
        val kept = mutableListOf<SleepSpan>()
        sleeps.sortedByDescending { asleepMinutes(it) }.forEach { s ->
            if (kept.none { it.start < s.end && s.start < it.end }) kept += s
        }
        return kept
    }

    private fun stageMinutesOrNull(sleep: SleepSpan, types: Set<Int>): Int? =
        if (sleep.stages.isEmpty()) null else stageMinutes(sleep, types)

    private fun stageMinutes(sleep: SleepSpan, types: Set<Int>): Int =
        (sleep.stages.filter { it.type in types }.sumOf { java.time.Duration.between(it.start, it.end).seconds } / 60).toInt()

    private fun minutes(start: Instant, end: Instant) = (java.time.Duration.between(start, end).seconds / 60).toInt()
}
