package com.healthanalyzer.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One running session (outdoor run, track run or treadmill) synced from Samsung Health.
 * Units: distance m, speed m/s, altitude m, calories kcal, heart rate bpm, cadence steps/min.
 */
@Entity(tableName = "runs")
data class RunEntity(
    /** Samsung data-point uid + "#" + session index (a data point can hold several sessions). */
    @PrimaryKey val id: String,
    val dataPointUid: String,
    val exerciseType: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val zoneOffsetSec: Int?,
    val title: String?,
    val durationSec: Long,
    val distanceM: Float?,
    val calories: Float,
    val meanHeartRate: Float?,
    val maxHeartRate: Float?,
    val minHeartRate: Float?,
    val meanSpeed: Float?,
    val maxSpeed: Float?,
    val meanCadence: Float?,
    val maxCadence: Float?,
    val altitudeGain: Float?,
    val altitudeLoss: Float?,
    val maxAltitude: Float?,
    val minAltitude: Float?,
    val meanPower: Float?,
    val maxPower: Float?,
    val vo2Max: Float?,
    val stepCount: Int?,
    val sampleCount: Int,
    val routePointCount: Int,
    val sourceAppId: String?,
    val sourceDeviceId: String?,
    val updatedEpochMs: Long?,
    // ---- Added in schema v2 (all nullable so existing rows migrate cleanly) ----
    /** Where the run came from: [SOURCE_SAMSUNG] (also when null, and for the Samsung export) or [SOURCE_GARMIN_FIT]. */
    val source: String? = null,
    /** Human-readable origin, e.g. "Garmin device 4315 · file 2026-11-01.fit". */
    val sourceDetail: String? = null,
    /** Running dynamics (Garmin FIT): average ground contact time, ms. */
    val groundContactMs: Float? = null,
    /** Average vertical oscillation, mm. */
    val verticalOscillationMm: Float? = null,
    /** Vertical oscillation / step length, %. */
    val verticalRatioPct: Float? = null,
    /** Average step length, mm. */
    val stepLengthMm: Float? = null,
    /** Ground contact time balance, % on the left foot. */
    val groundContactBalancePct: Float? = null,
    val aerobicTrainingEffect: Float? = null,
    val anaerobicTrainingEffect: Float? = null,
    // ---- Added in schema v3: Samsung Health export (Myotest running dynamics, sweat loss) ----
    /** Average effective flight time, ms. */
    val flightTimeMs: Float? = null,
    /** Average left/right asymmetry, % (positive = more on one side; Samsung reports a signed ratio). */
    val asymmetryPct: Float? = null,
    /** Stride regularity, % (100 = perfectly even strides). */
    val regularityPct: Float? = null,
    /** Leg stiffness, kN/m. */
    val stiffnessKnM: Float? = null,
    /** Estimated sweat loss, ml. */
    val sweatLossMl: Float? = null,
) {
    val sourceOrDefault: String get() = source ?: SOURCE_SAMSUNG
}

const val SOURCE_SAMSUNG = "SAMSUNG"
const val SOURCE_GARMIN_FIT = "GARMIN_FIT"

/** Heavy per-run data kept apart from [RunEntity] so list queries stay cheap. JSON-encoded. */
@Entity(tableName = "run_details")
data class RunDetailEntity(
    @PrimaryKey val runId: String,
    /** JSON array of [Sample]. */
    val samplesJson: String,
    /** JSON array of [RoutePoint]. */
    val routeJson: String,
)

/** One time-series point from ExerciseSession.log. */
data class Sample(
    val t: Long,
    val hr: Float?,
    val cadence: Float?,
    val speed: Float?,
    val power: Float?,
    /** Ground contact time, ms (Garmin FIT only). */
    val gct: Float? = null,
    /** Vertical oscillation, mm (Garmin FIT only). */
    val vo: Float? = null,
)

/** One GPS point from ExerciseSession.route. */
data class RoutePoint(
    val t: Long,
    val lat: Float,
    val lon: Float,
    val alt: Float?,
    val acc: Float?,
)

/**
 * One day's recovery markers from Samsung Health. Sleep belongs to the day the athlete woke up.
 * Every value can be missing (watch not worn, score not computed yet).
 */
@Entity(tableName = "daily_recovery")
data class DailyRecoveryEntity(
    /** Local date, ISO (yyyy-MM-dd). */
    @PrimaryKey val date: String,
    /** Samsung's sleep score for the main sleep, 0–100. */
    val sleepScore: Int? = null,
    /** Time asleep over all sleeps that ended this day, minutes. */
    val sleepMinutes: Int? = null,
    val deepMinutes: Int? = null,
    val remMinutes: Int? = null,
    val awakeMinutes: Int? = null,
    /** Start and end of the main sleep. */
    val bedtimeEpochMs: Long? = null,
    val wakeEpochMs: Long? = null,
    /** Samsung's energy score, 0–100. */
    val energyScore: Float? = null,
    /** Lowest sustained 30-minute heart rate of the day, bpm (see [RestingHeartRate]). */
    val restingHr: Int? = null,
) {
    /** Values from [newer] win; what it lacks is kept from this row. */
    fun mergedWith(newer: DailyRecoveryEntity) = DailyRecoveryEntity(
        date = date,
        sleepScore = newer.sleepScore ?: sleepScore,
        sleepMinutes = newer.sleepMinutes ?: sleepMinutes,
        deepMinutes = newer.deepMinutes ?: deepMinutes,
        remMinutes = newer.remMinutes ?: remMinutes,
        awakeMinutes = newer.awakeMinutes ?: awakeMinutes,
        bedtimeEpochMs = newer.bedtimeEpochMs ?: bedtimeEpochMs,
        wakeEpochMs = newer.wakeEpochMs ?: wakeEpochMs,
        energyScore = newer.energyScore ?: energyScore,
        restingHr = newer.restingHr ?: restingHr,
    )
}

/** A recorded session that is not a run: strength, hiking, cycling, walking and so on. */
@Entity(tableName = "other_workouts")
data class OtherWorkoutEntity(
    /** Samsung data-point uid + "#" + session index. */
    @PrimaryKey val id: String,
    /** Samsung's exercise type name, e.g. HIKING, BIKING, OTHER. */
    val exerciseType: String,
    val title: String?,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val zoneOffsetSec: Int?,
    val durationSec: Long,
    val distanceM: Float?,
    val calories: Float?,
    val meanHeartRate: Float?,
    val maxHeartRate: Float?,
    val altitudeGain: Float?,
    /** App that recorded it, e.g. a strength-training app writing into Samsung Health. */
    val sourceAppId: String?,
    /** Seconds spent at each heart rate, as "bpm:seconds,bpm:seconds"; null without heart-rate rows. */
    val hrSeconds: String?,
)

/** Seconds spent at each whole bpm, kept per workout so HR zones can be applied later. */
object HrHistogram {
    /**
     * [readings] are (epoch ms, bpm). A gap counts in full unless it is far longer than the
     * usual spacing of this log (a pause), so both 1 Hz watch logs and sparse logs work.
     */
    fun encode(readings: List<Pair<Long, Float>>): String? {
        val hr = readings.filter { it.second > 0 }.sortedBy { it.first }
        if (hr.size < 2) return null
        val gaps = hr.zipWithNext { a, b -> (b.first - a.first) / 1000.0 }.filter { it > 0 }
        if (gaps.isEmpty()) return null
        val limit = maxOf(10.0, gaps.sorted()[gaps.size / 2] * 3)
        val seconds = sortedMapOf<Int, Double>()
        hr.zipWithNext { a, b ->
            val dt = (b.first - a.first) / 1000.0
            if (dt > 0 && dt <= limit) seconds.merge(Math.round(b.second), dt, Double::plus)
        }
        return seconds.entries.joinToString(",") { "${it.key}:${Math.round(it.value)}" }.ifEmpty { null }
    }

    fun decode(text: String?): Map<Int, Double> = text.orEmpty().split(',').mapNotNull { part ->
        val (bpm, sec) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (bpm.toIntOrNull() ?: return@mapNotNull null) to (sec.toDoubleOrNull() ?: return@mapNotNull null)
    }.toMap()
}
