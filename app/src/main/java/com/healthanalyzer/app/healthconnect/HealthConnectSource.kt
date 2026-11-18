package com.healthanalyzer.app.healthconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthanalyzer.app.data.DailyRecoveryEntity
import com.healthanalyzer.app.data.HEALTH_CONNECT_ID_PREFIX
import com.healthanalyzer.app.data.HrHistogram
import com.healthanalyzer.app.data.OtherWorkoutEntity
import com.healthanalyzer.app.data.RoutePoint
import com.healthanalyzer.app.data.RunEntity
import com.healthanalyzer.app.data.SOURCE_HEALTH_CONNECT
import com.healthanalyzer.app.data.Sample
import com.healthanalyzer.app.samsung.SyncedRun
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Reads workouts, sleep and resting heart rate from Health Connect. Read only.
 *
 * This is an extra source next to Samsung Health: Health Connect has no running dynamics,
 * VO2max or sleep score, so a run that Samsung Health or Garmin also has is taken from there.
 * It covers what those don't: other watches and apps that write to Health Connect.
 */
class HealthConnectSource(context: Context) {

    private val appContext = context.applicationContext

    val sdkStatus: Int get() = HealthConnectClient.getSdkStatus(appContext, PROVIDER_PACKAGE)

    val isAvailable: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE

    /** Installed but too old: [installIntent] opens its store page. */
    val needsUpdate: Boolean get() = sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext, PROVIDER_PACKAGE) }

    suspend fun grantedPermissions(): Set<String> =
        if (isAvailable) client.permissionController.getGrantedPermissions() else emptySet()

    /** Everything to ask for. Only [READ_EXERCISE] is needed to sync; the rest adds detail. */
    fun permissionsToRequest(): Set<String> {
        val history = isAvailable && client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        return if (history) PERMISSIONS + HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY else PERMISSIONS
    }

    /** Intent that installs or updates the Health Connect app (Android 13 and below). */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.android.vending")
        data = Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
        putExtra("overlay", true)
        putExtra("callerId", appContext.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Intent that opens Health Connect's own settings, where access can be reviewed or removed. */
    fun manageIntent(): Intent {
        val action = if (Build.VERSION.SDK_INT >= 34) {
            "android.health.connect.action.HEALTH_HOME_SETTINGS"
        } else {
            HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
        }
        return Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---- Workouts ------------------------------------------------------------------------------

    /** Every exercise session that starts in [since, until), oldest first. Cheap: no series or route. */
    suspend fun readSessions(since: Instant, until: Instant): List<ExerciseSessionRecord> =
        readAll(ExerciseSessionRecord::class, TimeRangeFilter.between(since, until))

    /** Reads the series, route and totals of a running [session]. */
    suspend fun loadRun(session: ExerciseSessionRecord, granted: Set<String>): SyncedRun {
        val startMs = session.startTime.toEpochMilli()
        val endMs = session.endTime.toEpochMilli()
        val origin = session.metadata.dataOrigin.packageName
        val range = TimeRangeFilter.between(session.startTime, session.endTime)

        val hr = heartRate(session, granted)
        val speed = series(SpeedRecord::class, range, granted, origin, startMs, endMs) { r ->
            r.samples.map { it.time.toEpochMilli() to it.speed.inMetersPerSecond.toFloat() }
        }
        val cadence = series(StepsCadenceRecord::class, range, granted, origin, startMs, endMs) { r ->
            r.samples.map { it.time.toEpochMilli() to it.rate.toFloat() }
        }
        val power = series(PowerRecord::class, range, granted, origin, startMs, endMs) { r ->
            r.samples.map { it.time.toEpochMilli() to it.power.inWatts.toFloat() }
        }
        val samples: List<Sample> = HealthConnectMapping.mergeSeries(hr, speed, cadence, power)

        // Routes of other apps' sessions need the user's "always allow"; without it there is no route.
        val route = (session.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route.orEmpty().map {
            RoutePoint(
                t = it.time.toEpochMilli(),
                lat = it.latitude.toFloat(),
                lon = it.longitude.toFloat(),
                alt = it.altitude?.inMeters?.toFloat(),
                acc = it.horizontalAccuracy?.inMeters?.toFloat(),
            )
        }
        val totals = totals(range, granted)
        val durationSec = Duration.between(session.startTime, session.endTime).seconds
        val altitudes = route.mapNotNull { it.alt }
        fun mean(values: List<Float>) = values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val hrValues = hr.map { it.second }
        val speedValues = speed.map { it.second }
        val cadenceValues = cadence.map { it.second }.filter { it > 0 }
        val powerValues = power.map { it.second }

        return SyncedRun(
            run = RunEntity(
                id = HEALTH_CONNECT_ID_PREFIX + session.metadata.id,
                dataPointUid = session.metadata.id,
                exerciseType = HealthConnectMapping.typeName(session.exerciseType),
                startEpochMs = startMs,
                endEpochMs = endMs,
                zoneOffsetSec = session.startZoneOffset?.totalSeconds,
                title = session.title?.takeIf { it.isNotBlank() },
                durationSec = durationSec,
                distanceM = totals.distanceM,
                calories = totals.calories ?: 0f,
                meanHeartRate = mean(hrValues),
                maxHeartRate = hrValues.maxOrNull(),
                minHeartRate = hrValues.minOrNull(),
                meanSpeed = totals.distanceM?.takeIf { durationSec > 0 }?.let { it / durationSec } ?: mean(speedValues),
                maxSpeed = speedValues.maxOrNull(),
                meanCadence = mean(cadenceValues),
                maxCadence = cadenceValues.maxOrNull(),
                altitudeGain = totals.elevationGainM,
                altitudeLoss = null,
                maxAltitude = altitudes.maxOrNull(),
                minAltitude = altitudes.minOrNull(),
                meanPower = mean(powerValues),
                maxPower = powerValues.maxOrNull(),
                vo2Max = null,
                stepCount = totals.steps,
                sampleCount = samples.size,
                routePointCount = route.size,
                sourceAppId = origin,
                sourceDeviceId = null,
                updatedEpochMs = session.metadata.lastModifiedTime.toEpochMilli(),
                source = SOURCE_HEALTH_CONNECT,
                sourceDetail = "Health Connect · $origin",
            ),
            samples = samples,
            route = route,
        )
    }

    /** Reads the totals and heart rate of a [session] that is not a run. */
    suspend fun loadOtherWorkout(session: ExerciseSessionRecord, granted: Set<String>): OtherWorkoutEntity {
        val hr = heartRate(session, granted)
        val totals = totals(TimeRangeFilter.between(session.startTime, session.endTime), granted)
        val hrValues = hr.map { it.second }
        return OtherWorkoutEntity(
            id = HEALTH_CONNECT_ID_PREFIX + session.metadata.id,
            exerciseType = HealthConnectMapping.typeName(session.exerciseType),
            title = session.title?.takeIf { it.isNotBlank() },
            startEpochMs = session.startTime.toEpochMilli(),
            endEpochMs = session.endTime.toEpochMilli(),
            zoneOffsetSec = session.startZoneOffset?.totalSeconds,
            durationSec = Duration.between(session.startTime, session.endTime).seconds,
            distanceM = totals.distanceM,
            calories = totals.calories,
            meanHeartRate = hrValues.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            maxHeartRate = hrValues.maxOrNull(),
            altitudeGain = totals.elevationGainM,
            sourceAppId = session.metadata.dataOrigin.packageName,
            hrSeconds = HrHistogram.encode(hr),
        )
    }

    private suspend fun heartRate(session: ExerciseSessionRecord, granted: Set<String>): List<Pair<Long, Float>> =
        series(
            HeartRateRecord::class,
            TimeRangeFilter.between(session.startTime, session.endTime),
            granted,
            session.metadata.dataOrigin.packageName,
            session.startTime.toEpochMilli(),
            session.endTime.toEpochMilli(),
        ) { r -> r.samples.map { it.time.toEpochMilli() to it.beatsPerMinute.toFloat() } }

    /**
     * One series over a session, as (epoch ms, value), oldest first. When several apps wrote it,
     * the one that wrote the session wins; otherwise the app with the most points.
     */
    private suspend fun <T : Record> series(
        type: KClass<T>,
        range: TimeRangeFilter,
        granted: Set<String>,
        sessionOrigin: String,
        startMs: Long,
        endMs: Long,
        points: (T) -> List<Pair<Long, Float>>,
    ): List<Pair<Long, Float>> {
        if (HealthPermission.getReadPermission(type) !in granted) return emptyList()
        val byOrigin = readAll(type, range)
            .groupBy { it.metadata.dataOrigin.packageName }
            .mapValues { (_, records) -> records.flatMap(points).filter { it.first in startMs..endMs } }
            .filterValues { it.isNotEmpty() }
        val chosen = byOrigin[sessionOrigin] ?: byOrigin.values.maxByOrNull { it.size } ?: return emptyList()
        return chosen.sortedBy { it.first }.distinctBy { it.first }
    }

    private class Totals(val distanceM: Float?, val calories: Float?, val elevationGainM: Float?, val steps: Int?)

    /** Health Connect's own totals over [range], for whichever of them may be read. */
    private suspend fun totals(range: TimeRangeFilter, granted: Set<String>): Totals {
        fun allowed(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted
        val metrics = buildSet<AggregateMetric<*>> {
            if (allowed(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (allowed(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (allowed(ElevationGainedRecord::class)) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
            if (allowed(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
        }
        if (metrics.isEmpty()) return Totals(null, null, null, null)
        val result = client.aggregate(AggregateRequest(metrics, range))
        fun <T : Any> get(metric: AggregateMetric<T>): T? = if (metric in metrics) result[metric] else null
        return Totals(
            distanceM = get(DistanceRecord.DISTANCE_TOTAL)?.inMeters?.toFloat()?.takeIf { it > 0 },
            calories = get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories?.toFloat()?.takeIf { it > 0 },
            elevationGainM = get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters?.toFloat(),
            steps = get(StepsRecord.COUNT_TOTAL)?.toInt()?.takeIf { it > 0 },
        )
    }

    // ---- Recovery ------------------------------------------------------------------------------

    /**
     * Sleep and resting heart rate per day in [from]..[to] (local dates), for whichever of them
     * is in [granted]. Sleep is filed under the day it ended. Health Connect has no sleep score.
     */
    suspend fun readRecovery(from: LocalDate, to: LocalDate, granted: Set<String>): List<DailyRecoveryEntity> {
        val zone = ZoneId.systemDefault()
        val range = TimeRangeFilter.between(
            from.minusDays(1).atStartOfDay(zone).toInstant(),
            to.plusDays(1).atStartOfDay(zone).toInstant(),
        )
        val days = sortedMapOf<LocalDate, DailyRecoveryEntity>()
        fun update(date: LocalDate, change: (DailyRecoveryEntity) -> DailyRecoveryEntity) {
            if (date in from..to) days[date] = change(days[date] ?: DailyRecoveryEntity(date.toString()))
        }

        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            readAll(SleepSessionRecord::class, range)
                .groupBy { it.endTime.atOffset(it.endZoneOffset ?: zone.rules.getOffset(it.endTime)).toLocalDate() }
                .forEach { (date, records) ->
                    val sleeps = HealthConnectMapping.withoutOverlaps(
                        records.map { r -> SleepSpan(r.startTime, r.endTime, r.stages.map { SleepStage(it.startTime, it.endTime, it.stage) }) },
                    )
                    val main = sleeps.maxBy { HealthConnectMapping.asleepMinutes(it) }
                    update(date) {
                        it.copy(
                            sleepMinutes = sleeps.sumOf { s -> HealthConnectMapping.asleepMinutes(s) }.takeIf { m -> m > 0 },
                            deepMinutes = HealthConnectMapping.deepMinutes(main),
                            remMinutes = HealthConnectMapping.remMinutes(main),
                            awakeMinutes = HealthConnectMapping.awakeMinutes(main),
                            bedtimeEpochMs = main.start.toEpochMilli(),
                            wakeEpochMs = main.end.toEpochMilli(),
                        )
                    }
                }
        }
        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
            readAll(RestingHeartRateRecord::class, range)
                .filter { it.beatsPerMinute in 25..220 }
                .groupBy { it.time.atOffset(it.zoneOffset ?: zone.rules.getOffset(it.time)).toLocalDate() }
                .forEach { (date, records) -> update(date) { it.copy(restingHr = records.minOf { r -> r.beatsPerMinute }.toInt()) } }
        }
        return days.values.toList()
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val all = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageSize = PAGE_SIZE, pageToken = token))
            all += response.records
            token = response.pageToken
        } while (!token.isNullOrEmpty())
        return all
    }

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val PAGE_SIZE = 1000

        val READ_EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

        /** Lets Health Connect return GPS routes recorded by other apps without asking per workout. */
        const val READ_EXERCISE_ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"

        /** The read permissions declared in the manifest (history access is added when the phone supports it). */
        val PERMISSIONS: Set<String> = setOf(
            READ_EXERCISE,
            READ_EXERCISE_ROUTES,
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(StepsCadenceRecord::class),
            HealthPermission.getReadPermission(PowerRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        )
    }
}
