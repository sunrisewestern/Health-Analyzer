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
