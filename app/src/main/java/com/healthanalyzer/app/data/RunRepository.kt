package com.healthanalyzer.app.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.healthanalyzer.app.samsung.SamsungHealthSource
import com.healthanalyzer.app.samsung.SyncedRun
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class RunDetail(
    val run: RunEntity,
    val samples: List<Sample>,
    val route: List<RoutePoint>,
)

/** What a Health Connect sync did. [duplicates] are workouts another source already had. */
data class HealthConnectSyncResult(val runs: Int, val workouts: Int, val duplicates: Int, val recoveryDays: Int)

class RunRepository(
    private val dao: RunDao,
    private val source: SamsungHealthSource,
    private val gson: Gson = Gson(),
    /** Remembers one-time sync steps; null in tests. */
    private val prefs: android.content.SharedPreferences? = null,
) {
    fun observeRuns() = dao.observeRuns()

    /**
     * Pulls runs from Samsung Health into the local database.
     * Incremental by default: re-reads from a little before the newest stored run, so
     * edits to recent runs are picked up too. [full] re-reads the whole history.
     * Returns the number of runs read.
     */
    suspend fun sync(full: Boolean): Int {
        val latest = if (full) null else dao.latestStart()
        val since = latest?.let { Instant.ofEpochMilli(it).minus(OVERLAP) } ?: HISTORY_START
        // Installs from before other workouts were stored read a few months back once to fill them in.
        val backfill = !full && prefs?.getBoolean(OTHER_BACKFILLED, false) == false
        val from = if (backfill) minOf(since, Instant.now().minus(BACKFILL)) else since
        val (runs, others) = source.readExercise(from, Instant.now().plusSeconds(60))
        val (keep, _) = withoutCrossSourceDuplicates(runs)
        store(keep)
        replaceHealthConnectWorkouts(others)
        others.chunked(200).forEach { dao.upsertOtherWorkouts(it) }
        prefs?.edit()?.putBoolean(OTHER_BACKFILLED, true)?.apply()
        // Recovery data is an extra: a failure here must not fail the run sync.
        runCatching { syncRecovery(full) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        return runs.size
    }

    /**
     * Pulls sleep, energy score and resting heart rate per day, for whichever of them Samsung
     * Health lets the app read. Re-reads the last few stored days, which can still change.
     * Returns the number of days stored.
     */
    suspend fun syncRecovery(full: Boolean): Int {
        val granted = source.grantedRecoveryPermissions()
        if (granted.isEmpty()) return 0
        val today = LocalDate.now()
        val latest = if (full) null else dao.latestRecoveryDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val from = latest?.minusDays(3) ?: today.minusDays(if (full) RECOVERY_FULL_DAYS else RECOVERY_FIRST_DAYS)
        val read = source.readRecovery(from, today, granted)
        if (read.isEmpty()) return 0
        val existing = dao.recoveryBetween(from.toString(), today.toString()).associateBy { it.date }
        dao.upsertRecovery(read.map { existing[it.date]?.mergedWith(it) ?: it })
        return read.size
    }

    /**
     * Pulls workouts, sleep and resting heart rate from Health Connect. A workout that another
     * source already has (same start within 2 minutes) is skipped before its series are read:
     * Samsung Health and Garmin carry more detail, and Samsung Health mirrors its own workouts
     * into Health Connect. Incremental by default; [full] re-reads everything Health Connect returns.
     */
    suspend fun syncHealthConnect(
        hc: com.healthanalyzer.app.healthconnect.HealthConnectSource,
        full: Boolean,
        onProgress: (String) -> Unit = {},
    ): HealthConnectSyncResult {
        val granted = hc.grantedPermissions()
        val now = Instant.now()
        val syncedUntil = if (full) null else prefs?.getLong(HC_SYNCED_UNTIL, 0L)?.takeIf { it > 0 }
        val since = syncedUntil?.let { Instant.ofEpochMilli(it).minus(OVERLAP) } ?: HISTORY_START
        val sessions = hc.readSessions(since, now.plusSeconds(60))
        var runs = 0
        var workouts = 0
        var duplicates = 0
        sessions.forEachIndexed { index, session ->
            onProgress("Reading Health Connect workouts ${index + 1}/${sessions.size}…")
            val start = session.startTime.toEpochMilli()
            if (com.healthanalyzer.app.healthconnect.HealthConnectMapping.isRun(session.exerciseType)) {
                val taken = dao.runsStartingBetween(start - DUP_WINDOW_MS, start + DUP_WINDOW_MS)
                    .any { it.sourceOrDefault != SOURCE_HEALTH_CONNECT }
                if (taken) duplicates++ else { store(listOf(hc.loadRun(session, granted))); runs++ }
            } else {
                val taken = dao.otherWorkoutsStartingBetween(start - DUP_WINDOW_MS, start + DUP_WINDOW_MS)
                    .any { !it.id.startsWith(HEALTH_CONNECT_ID_PREFIX) }
                if (taken) duplicates++ else { dao.upsertOtherWorkouts(listOf(hc.loadOtherWorkout(session, granted))); workouts++ }
            }
        }
        prefs?.edit()?.putLong(HC_SYNCED_UNTIL, now.toEpochMilli())?.apply()

        // Recovery is an extra: a failure here must not fail the workout sync.
        val recoveryDays = runCatching {
            onProgress("Reading Health Connect sleep and resting heart rate…")
            val today = LocalDate.now()
            val from = if (syncedUntil == null) today.minusDays(if (full) RECOVERY_FULL_DAYS else RECOVERY_FIRST_DAYS) else today.minusDays(RECOVERY_RECENT_DAYS)
            val read = hc.readRecovery(from, today, granted)
            val existing = dao.recoveryBetween(from.toString(), today.toString()).associateBy { it.date }
            // What Samsung Health already stored for a day is kept; Health Connect only fills the gaps.
            if (read.isNotEmpty()) dao.upsertRecovery(read.map { day -> existing[day.date]?.let { day.mergedWith(it) } ?: day })
            read.size
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(0)
        return HealthConnectSyncResult(runs, workouts, duplicates, recoveryDays)
    }

    /** Stores runs read from FIT files. Returns (stored, skipped as duplicates of another source). */
    suspend fun importRuns(runs: List<SyncedRun>): Pair<Int, Int> {
        val (keep, dupes) = withoutCrossSourceDuplicates(runs)
        store(keep)
        return keep.size to dupes
    }

    /**
     * Drops runs that another source already has: same start within 2 minutes and distance
     * within 5% (e.g. one run recorded by both a Galaxy Watch and a Garmin). The first source
     * stored wins. Re-importing the same source simply updates its own rows.
     *
     * A Health Connect copy never wins: it is the least detailed, so it is removed when the same
     * run arrives from Samsung Health or Garmin.
     */
    private suspend fun withoutCrossSourceDuplicates(runs: List<SyncedRun>): Pair<List<SyncedRun>, Int> {
        var dupes = 0
        val keep = runs.filter { candidate ->
            val r = candidate.run
            val nearby = dao.runsStartingBetween(r.startEpochMs - DUP_WINDOW_MS, r.startEpochMs + DUP_WINDOW_MS)
            val (fromHealthConnect, rivals) = nearby
                .filter { it.id != r.id && it.sourceOrDefault != r.sourceOrDefault }
                .partition { it.sourceOrDefault == SOURCE_HEALTH_CONNECT }
            val duplicate = rivals.any { other -> sameDistance(other.distanceM, r.distanceM) }
            if (!duplicate && fromHealthConnect.isNotEmpty()) {
                val ids = fromHealthConnect.map { it.id }
                dao.deleteDetails(ids)
                dao.deleteRuns(ids)
            }
            if (duplicate) dupes++
            !duplicate
        }
        return keep to dupes
    }

    /** Removes the Health Connect copies of [workouts] that now arrive from Samsung Health. */
    private suspend fun replaceHealthConnectWorkouts(workouts: List<OtherWorkoutEntity>) {
        if (workouts.isEmpty()) return
        val copies = dao.healthConnectWorkouts()
        if (copies.isEmpty()) return
        val starts = workouts.map { it.startEpochMs }.sorted()
        val ids = copies.filter { copy ->
            val i = starts.binarySearch(copy.startEpochMs).let { if (it < 0) -it - 1 else it }
            listOfNotNull(starts.getOrNull(i), starts.getOrNull(i - 1)).any { kotlin.math.abs(it - copy.startEpochMs) <= DUP_WINDOW_MS }
        }.map { it.id }
        ids.chunked(500).forEach { dao.deleteOtherWorkouts(it) }
    }

    private fun sameDistance(a: Float?, b: Float?): Boolean {
        if (a == null || b == null) return a == b
        val larger = maxOf(a, b)
        return larger == 0f || kotlin.math.abs(a - b) / larger <= 0.05f
    }

    /** Deletes every stored run and its samples/route. Returns how many runs were removed. */
    suspend fun eraseAll(): Int {
        val count = dao.runCount()
        dao.deleteAllDetails()
        dao.deleteAllRuns()
        dao.deleteAllOtherWorkouts()
        dao.deleteAllRecovery()
        prefs?.edit()?.remove(HC_SYNCED_UNTIL)?.apply()
        return count
    }

    private suspend fun store(runs: List<SyncedRun>) {
        if (runs.isEmpty()) return
        val existing = runs.map { it.run.id }.chunked(500).flatMap { dao.runsByIds(it) }.associateBy { it.id }
        dao.upsertRuns(runs.map { r -> existing[r.run.id]?.let { fillNulls(r.run, it) } ?: r.run })
        dao.upsertDetails(runs.map {
            RunDetailEntity(
                runId = it.run.id,
                samplesJson = gson.toJson(it.samples),
                routeJson = gson.toJson(it.route),
            )
        })
    }

    /**
     * Two sources describe the same Samsung run: the Data SDK (no running dynamics or sweat loss)
     * and the data export (no altitude loss, sometimes no title). Whichever is stored second, a
     * value it lacks never wipes one already stored.
     */
    private fun fillNulls(new: RunEntity, old: RunEntity) = new.copy(
        zoneOffsetSec = new.zoneOffsetSec ?: old.zoneOffsetSec,
        title = new.title ?: old.title,
        distanceM = new.distanceM ?: old.distanceM,
        meanHeartRate = new.meanHeartRate ?: old.meanHeartRate,
        maxHeartRate = new.maxHeartRate ?: old.maxHeartRate,
        minHeartRate = new.minHeartRate ?: old.minHeartRate,
        meanSpeed = new.meanSpeed ?: old.meanSpeed,
        maxSpeed = new.maxSpeed ?: old.maxSpeed,
        meanCadence = new.meanCadence ?: old.meanCadence,
        maxCadence = new.maxCadence ?: old.maxCadence,
        altitudeGain = new.altitudeGain ?: old.altitudeGain,
        altitudeLoss = new.altitudeLoss ?: old.altitudeLoss,
        maxAltitude = new.maxAltitude ?: old.maxAltitude,
        minAltitude = new.minAltitude ?: old.minAltitude,
        meanPower = new.meanPower ?: old.meanPower,
        maxPower = new.maxPower ?: old.maxPower,
        vo2Max = new.vo2Max ?: old.vo2Max,
        stepCount = new.stepCount ?: old.stepCount,
        sourceAppId = new.sourceAppId ?: old.sourceAppId,
        sourceDeviceId = new.sourceDeviceId ?: old.sourceDeviceId,
        sourceDetail = new.sourceDetail ?: old.sourceDetail,
        groundContactMs = new.groundContactMs ?: old.groundContactMs,
        verticalOscillationMm = new.verticalOscillationMm ?: old.verticalOscillationMm,
        verticalRatioPct = new.verticalRatioPct ?: old.verticalRatioPct,
        stepLengthMm = new.stepLengthMm ?: old.stepLengthMm,
        groundContactBalancePct = new.groundContactBalancePct ?: old.groundContactBalancePct,
        aerobicTrainingEffect = new.aerobicTrainingEffect ?: old.aerobicTrainingEffect,
        anaerobicTrainingEffect = new.anaerobicTrainingEffect ?: old.anaerobicTrainingEffect,
        flightTimeMs = new.flightTimeMs ?: old.flightTimeMs,
        asymmetryPct = new.asymmetryPct ?: old.asymmetryPct,
        regularityPct = new.regularityPct ?: old.regularityPct,
        stiffnessKnM = new.stiffnessKnM ?: old.stiffnessKnM,
        sweatLossMl = new.sweatLossMl ?: old.sweatLossMl,
    )

    /** Start of the newest run downloaded from Garmin Connect, or null. */
    suspend fun latestGarminStart(): Long? = dao.latestGarminStart()

    /** Garmin Connect activity ids of the runs stored (from "Garmin Connect activity <id> · …"). */
    suspend fun garminActivityIds(): Set<String> =
        dao.garminSourceDetails().mapNotNull { GARMIN_ACTIVITY.find(it)?.groupValues?.get(1) }.toSet()

    suspend fun latestRunId(): String? = dao.latestRun()?.id

    suspend fun getDetail(id: String): RunDetail? {
        val run = dao.getRun(id) ?: return null
        val detail = dao.getDetail(id)
        return RunDetail(
            run = run,
            samples = detail?.samplesJson?.let { gson.fromJson<List<Sample>>(it, sampleListType) }.orEmpty(),
            route = detail?.routeJson?.let { gson.fromJson<List<RoutePoint>>(it, routeListType) }.orEmpty(),
        )
    }

    private companion object {
        val GARMIN_ACTIVITY = Regex("""Garmin Connect activity (\d+)""")
        val HISTORY_START: Instant = Instant.parse("2012-01-01T00:00:00Z")
        val OVERLAP: Duration = Duration.ofDays(3)
        private val BACKFILL: Duration = Duration.ofDays(120)
        private const val OTHER_BACKFILLED = "otherWorkoutsBackfilled"
        private const val RECOVERY_FIRST_DAYS = 90L
        private const val RECOVERY_FULL_DAYS = 365L
        private const val RECOVERY_RECENT_DAYS = 7L
        private const val HC_SYNCED_UNTIL = "healthConnectSyncedUntil"
        const val DUP_WINDOW_MS = 2 * 60_000L
        val sampleListType = object : TypeToken<List<Sample>>() {}.type
        val routeListType = object : TypeToken<List<RoutePoint>>() {}.type
    }
}
