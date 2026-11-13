package com.healthanalyzer.app.samsung

import android.app.Activity
import android.content.Context
import com.healthanalyzer.app.data.RoutePoint
import com.healthanalyzer.app.data.RunEntity
import com.healthanalyzer.app.data.Sample
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.healthanalyzer.app.data.DailyRecoveryEntity
import com.healthanalyzer.app.data.HrHistogram
import com.healthanalyzer.app.data.HrReading
import com.healthanalyzer.app.data.OtherWorkoutEntity
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.healthanalyzer.app.data.RestingHeartRate
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Athlete details found in Samsung Health. Any field can be missing. */
data class SamsungProfile(
    val nickname: String? = null,
    val birthDate: LocalDate? = null,
    val sex: String? = null,
    val heightCm: Float? = null,
    val weightKg: Float? = null,
    val bodyFatPercent: Float? = null,
    val bodyMeasuredOn: LocalDate? = null,
    /** Median over recent days of each day's lowest 30-minute average HR (an estimate of resting HR). */
    val restingHeartRate: Int? = null,
    val restingHeartRateDays: Int = 0,
)

/** A run as read from Samsung Health, before it is stored. */
data class SyncedRun(
    val run: RunEntity,
    val samples: List<Sample>,
    val route: List<RoutePoint>,
)

/**
 * Reads running sessions from Samsung Health through the Samsung Health Data SDK.
 *
 * Without a Samsung partnership this only works while
 * "Developer Mode for Data Read" is on in Samsung Health
 * (Settings > About Samsung Health > tap the version 10x > Developer mode).
 */
class SamsungHealthSource(context: Context) {

    private val store: HealthDataStore = HealthDataService.getStore(context.applicationContext)

    val permissions: Set<Permission> = setOf(
        Permission.of(DataTypes.EXERCISE, AccessType.READ),
        // Needed to receive ExerciseSession.route (GPS).
        Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ),
    )

    suspend fun hasAllPermissions(): Boolean =
        store.getGrantedPermissions(permissions).containsAll(permissions)

    /** Sleep, energy score and all-day heart rate. Nice to have: runs sync without them. */
    val recoveryPermissions: Set<Permission> = setOf(
        Permission.of(DataTypes.SLEEP, AccessType.READ),
        Permission.of(DataTypes.ENERGY_SCORE, AccessType.READ),
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
    )

    /**
     * Shows Samsung Health's permission screen, asking for the recovery data too. Returns true
     * if everything needed for runs was granted.
     */
    suspend fun requestPermissions(activity: Activity): Boolean =
        store.requestPermissions(permissions + recoveryPermissions, activity).containsAll(permissions)

    suspend fun grantedRecoveryPermissions(): Set<Permission> = store.getGrantedPermissions(recoveryPermissions)

    /** Asks for the recovery permissions that are still missing; the answer does not affect run sync. */
    suspend fun requestRecoveryPermissions(activity: Activity) {
        if (!grantedRecoveryPermissions().containsAll(recoveryPermissions)) store.requestPermissions(recoveryPermissions, activity)
    }

    /** Reads every running-type session that starts in [since, until). */
    suspend fun readRuns(since: Instant, until: Instant): List<SyncedRun> = readExercise(since, until).first

    /** Reads every session that starts in [since, until): runs, and everything else as [OtherWorkoutEntity]. */
    suspend fun readExercise(since: Instant, until: Instant): Pair<List<SyncedRun>, List<OtherWorkoutEntity>> {
        val result = mutableListOf<SyncedRun>()
        val others = mutableListOf<OtherWorkoutEntity>()
        var pageToken: String? = null
        do {
            val builder = DataTypes.EXERCISE.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(since, until))
                .setOrdering(Ordering.ASC)
                // Sessions carry per-second logs and GPS routes, so keep pages small.
                .setPageSize(PAGE_SIZE)
            pageToken?.let { builder.setPageToken(it) }

            val response = store.readData(builder.build())
            response.dataList.forEach { point ->
                result += toRuns(point)
                others += toOtherWorkouts(point)
            }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return result to others
    }

    private fun toOtherWorkouts(point: HealthDataPoint): List<OtherWorkoutEntity> {
        val sessions: List<ExerciseSession> =
            point.getValue(DataType.ExerciseType.SESSIONS) ?: return emptyList()
        return sessions.mapIndexedNotNull { index, s ->
            if (s.exerciseType in RUNNING_TYPES) return@mapIndexedNotNull null
            OtherWorkoutEntity(
                id = "${point.uid}#$index",
                exerciseType = s.exerciseType.name,
                title = s.customTitle ?: point.getValue(DataType.ExerciseType.CUSTOM_TITLE),
                startEpochMs = s.startTime.toEpochMilli(),
                endEpochMs = s.endTime.toEpochMilli(),
                zoneOffsetSec = point.zoneOffset?.totalSeconds,
                durationSec = s.duration.seconds,
                distanceM = s.distance,
                calories = s.calories,
                meanHeartRate = s.meanHeartRate,
                maxHeartRate = s.maxHeartRate,
                altitudeGain = s.altitudeGain,
                sourceAppId = point.dataSource?.appId,
                hrSeconds = HrHistogram.encode(
                    s.log.orEmpty().mapNotNull { row -> row.heartRate?.let { row.timestamp.toEpochMilli() to it } },
                ),
            )
        }
    }

    // ---- Recovery ------------------------------------------------------------------------------

    /**
     * Sleep, energy score and resting heart rate per day in [from]..[to] (local dates), for
     * whichever of them is in [granted]. Sleep is filed under the day it ended.
     */
    suspend fun readRecovery(from: LocalDate, to: LocalDate, granted: Set<Permission>): List<DailyRecoveryEntity> {
        val days = sortedMapOf<LocalDate, DailyRecoveryEntity>()
        fun update(date: LocalDate, change: (DailyRecoveryEntity) -> DailyRecoveryEntity) {
            if (date in from..to) days[date] = change(days[date] ?: DailyRecoveryEntity(date.toString()))
        }

        if (Permission.of(DataTypes.SLEEP, AccessType.READ) in granted) {
            // A night's sleep starts the evening before the day it counts for.
            readPaged { token ->
                DataTypes.SLEEP.readDataRequestBuilder
                    .setLocalTimeFilter(LocalTimeFilter.of(from.minusDays(1).atStartOfDay(), to.plusDays(1).atStartOfDay()))
                    .setOrdering(Ordering.ASC)
                    .setPageSize(100)
                    .apply { token?.let { setPageToken(it) } }
                    .build()
            }.groupBy { p -> (p.endTime ?: p.startTime).atOffset(p.zoneOffset ?: ZoneOffset.UTC).toLocalDate() }
                .forEach { (date, sleeps) ->
                    fun asleep(p: HealthDataPoint) = stageMinutes(p).let { st ->
                        if (st.isEmpty()) p.getValue(DataType.SleepType.DURATION)?.toMinutes()?.toInt() ?: 0
                        else st.filterKeys { it != DataType.SleepType.StageType.AWAKE }.values.sum()
                    }
                    val main = sleeps.maxBy { asleep(it) }
                    val stages = stageMinutes(main)
                    update(date) {
                        it.copy(
                            sleepScore = main.getValue(DataType.SleepType.SLEEP_SCORE)?.takeIf { s -> s > 0 },
                            sleepMinutes = sleeps.sumOf { s -> asleep(s) }.takeIf { m -> m > 0 },
                            deepMinutes = stages[DataType.SleepType.StageType.DEEP],
                            remMinutes = stages[DataType.SleepType.StageType.REM],
                            awakeMinutes = stages[DataType.SleepType.StageType.AWAKE],
                            bedtimeEpochMs = main.startTime.toEpochMilli(),
                            wakeEpochMs = main.endTime?.toEpochMilli(),
                        )
                    }
                }
        }
        if (Permission.of(DataTypes.ENERGY_SCORE, AccessType.READ) in granted) {
            readPaged { token ->
                DataTypes.ENERGY_SCORE.readDataRequestBuilder
                    .setLocalDateFilter(LocalDateFilter.of(from, to.plusDays(1)))
                    .setPageSize(500)
                    .apply { token?.let { setPageToken(it) } }
                    .build()
            }.forEach { p ->
                val score = p.getValue(DataType.EnergyScoreType.ENERGY_SCORE)?.takeIf { it > 0 } ?: return@forEach
                // Daily points are stamped with the day at 00:00 UTC, whatever the time zone.
                val utc = p.startTime.atOffset(ZoneOffset.UTC)
                val date = if (utc.toLocalTime() == java.time.LocalTime.MIDNIGHT) utc.toLocalDate()
                else p.startTime.atOffset(p.zoneOffset ?: ZoneOffset.UTC).toLocalDate()
                update(date) { it.copy(energyScore = score) }
            }
        }
        if (Permission.of(DataTypes.HEART_RATE, AccessType.READ) in granted) {
            RestingHeartRate.daily(readHeartRate(from.atStartOfDay(), to.plusDays(1).atStartOfDay())).forEach { (date, bpm) ->
                update(date) { it.copy(restingHr = Math.round(bpm).toInt()) }
            }
        }
        return days.values.toList()
    }

    private fun stageMinutes(sleep: HealthDataPoint): Map<DataType.SleepType.StageType, Int> =
        sleep.getValue(DataType.SleepType.SESSIONS).orEmpty().flatMap { it.stages.orEmpty() }
            .groupBy { it.stage }
            .mapValues { (_, list) -> list.sumOf { Duration.between(it.startTime, it.endTime).seconds }.div(60).toInt() }

    private suspend fun readPaged(
        request: (pageToken: String?) -> com.samsung.android.sdk.health.data.request.ReadDataRequest<HealthDataPoint>,
    ): List<HealthDataPoint> {
        val all = mutableListOf<HealthDataPoint>()
        var token: String? = null
        do {
            val response = store.readData(request(token))
            all += response.dataList
            token = response.pageToken
        } while (!token.isNullOrEmpty())
        return all
    }

    private fun toRuns(point: HealthDataPoint): List<SyncedRun> {
        val sessions: List<ExerciseSession> =
            point.getValue(DataType.ExerciseType.SESSIONS) ?: return emptyList()
        return sessions.mapIndexedNotNull { index, s ->
            if (s.exerciseType !in RUNNING_TYPES) return@mapIndexedNotNull null
            val samples = s.log.orEmpty().map {
                Sample(
                    t = it.timestamp.toEpochMilli(),
                    hr = it.heartRate,
                    cadence = it.cadence,
                    speed = it.speed,
                    power = it.power,
                )
            }
            val route = s.route.orEmpty().map {
                RoutePoint(
                    t = it.timestamp.toEpochMilli(),
                    lat = it.latitude,
                    lon = it.longitude,
                    alt = it.altitude,
                    acc = it.accuracy,
                )
            }
            SyncedRun(
                run = RunEntity(
                    id = "${point.uid}#$index",
                    dataPointUid = point.uid,
                    exerciseType = s.exerciseType.name,
                    startEpochMs = s.startTime.toEpochMilli(),
                    endEpochMs = s.endTime.toEpochMilli(),
                    zoneOffsetSec = point.zoneOffset?.totalSeconds,
                    title = s.customTitle ?: point.getValue(DataType.ExerciseType.CUSTOM_TITLE),
                    durationSec = s.duration.seconds,
                    distanceM = s.distance,
                    calories = s.calories,
                    meanHeartRate = s.meanHeartRate,
                    maxHeartRate = s.maxHeartRate,
                    minHeartRate = s.minHeartRate,
                    meanSpeed = s.meanSpeed,
                    maxSpeed = s.maxSpeed,
                    meanCadence = s.meanCadence,
                    maxCadence = s.maxCadence,
                    altitudeGain = s.altitudeGain,
                    altitudeLoss = s.altitudeLoss,
                    maxAltitude = s.maxAltitude,
                    minAltitude = s.minAltitude,
                    meanPower = s.meanPower,
                    maxPower = s.maxPower,
                    vo2Max = s.vo2Max,
                    stepCount = s.count,
                    sampleCount = samples.size,
                    routePointCount = route.size,
                    sourceAppId = point.dataSource?.appId,
                    sourceDeviceId = point.dataSource?.deviceId,
                    updatedEpochMs = point.updateTime?.toEpochMilli(),
                    source = com.healthanalyzer.app.data.SOURCE_SAMSUNG,
                ),
                samples = samples,
                route = route,
            )
        }
    }

    // ---- Athlete profile ----------------------------------------------------------------------

    val profilePermissions: Set<Permission> = setOf(
        Permission.of(DataTypes.USER_PROFILE, AccessType.READ),
        Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ),
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
    )

    /** Asks only for what isn't granted yet. Returns the profile permissions now granted. */
    suspend fun ensureProfilePermissions(activity: Activity): Set<Permission> {
        val granted = store.getGrantedPermissions(profilePermissions)
        if (granted.containsAll(profilePermissions)) return granted
        return store.requestPermissions(profilePermissions, activity)
    }

    /** Reads whatever profile data is available and granted; missing pieces stay null. */
    suspend fun readProfile(granted: Set<Permission>): SamsungProfile {
        var profile = SamsungProfile()
        if (Permission.of(DataTypes.USER_PROFILE, AccessType.READ) in granted) {
            val user = store.readData(DataTypes.USER_PROFILE.readDataRequestBuilder.build()).dataList.firstOrNull()
            if (user != null) {
                val gender = user.getValue(DataType.UserProfileDataType.GENDER)
                profile = profile.copy(
                    nickname = user.getValue(DataType.UserProfileDataType.NICKNAME)?.takeIf { it.isNotBlank() },
                    birthDate = parseBirthDate(user.getValue(DataType.UserProfileDataType.DATE_OF_BIRTH)),
                    sex = when (gender) {
                        DataType.UserProfileDataType.Gender.GENDER_MALE -> "male"
                        DataType.UserProfileDataType.Gender.GENDER_FEMALE -> "female"
                        else -> null
                    },
                    heightCm = user.getValue(DataType.UserProfileDataType.HEIGHT)?.takeIf { it > 0 },
                    weightKg = user.getValue(DataType.UserProfileDataType.WEIGHT)?.takeIf { it > 0 },
                )
            }
        }
        if (Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ) in granted) {
            val latest = store.readData(
                DataTypes.BODY_COMPOSITION.readDataRequestBuilder
                    .setInstantTimeFilter(InstantTimeFilter.since(Instant.now().minus(Duration.ofDays(730))))
                    .setOrdering(Ordering.DESC)
                    .setLimit(1)
                    .build(),
            ).dataList.firstOrNull()
            if (latest != null) {
                profile = profile.copy(
                    weightKg = latest.getValue(DataType.BodyCompositionType.WEIGHT)?.takeIf { it > 0 } ?: profile.weightKg,
                    heightCm = profile.heightCm ?: latest.getValue(DataType.BodyCompositionType.HEIGHT)?.takeIf { it > 0 },
                    bodyFatPercent = latest.getValue(DataType.BodyCompositionType.BODY_FAT)?.takeIf { it > 0 },
                    bodyMeasuredOn = latest.startTime.atOffset(latest.zoneOffset ?: java.time.ZoneOffset.UTC).toLocalDate(),
                )
            }
        }
        if (Permission.of(DataTypes.HEART_RATE, AccessType.READ) in granted) {
            RestingHeartRate.estimate(readHeartRate(RESTING_HR_DAYS))?.let {
                profile = profile.copy(restingHeartRate = it.bpm, restingHeartRateDays = it.days)
            }
        }
        return profile
    }

    /**
     * All-day heart-rate readings for the last [days] full days (today excluded: it is partial).
     * Watch data usually arrives as binned points with a per-minute series inside; use the series
     * when present, otherwise the point's own average.
     */
    private suspend fun readHeartRate(days: Int): List<HrReading> {
        val today = LocalDate.now().atStartOfDay()
        return readHeartRate(today.minusDays(days.toLong()), today)
    }

    private suspend fun readHeartRate(from: java.time.LocalDateTime, until: java.time.LocalDateTime): List<HrReading> {
        val readings = mutableListOf<HrReading>()
        var pageToken: String? = null
        do {
            val builder = DataTypes.HEART_RATE.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(from, until))
                .setOrdering(Ordering.ASC)
                .setPageSize(HR_PAGE_SIZE)
            pageToken?.let { builder.setPageToken(it) }
            val response = store.readData(builder.build())
            response.dataList.forEach { point ->
                val offset = point.zoneOffset ?: ZoneOffset.UTC
                val series = point.getValue(DataType.HeartRateType.SERIES_DATA).orEmpty()
                if (series.isNotEmpty()) {
                    series.forEach { readings += HrReading(it.startTime.atOffset(offset).toLocalDateTime(), it.heartRate) }
                } else {
                    point.getValue(DataType.HeartRateType.HEART_RATE)?.let {
                        readings += HrReading(point.startTime.atOffset(offset).toLocalDateTime(), it)
                    }
                }
            }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return readings
    }

    /** Samsung returns the birth date as a string; accept yyyyMMdd and ISO yyyy-MM-dd. */
    private fun parseBirthDate(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { LocalDate.parse(s) }.getOrNull()
            ?: runCatching { LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
    }

    companion object {
        private const val PAGE_SIZE = 5
        private const val RESTING_HR_DAYS = 14
        private const val HR_PAGE_SIZE = 1000

        val RUNNING_TYPES = setOf(
            DataType.ExerciseType.PredefinedExerciseType.RUNNING,
            DataType.ExerciseType.PredefinedExerciseType.TRACK_RUNNING,
            DataType.ExerciseType.PredefinedExerciseType.TREADMILL,
        )
    }
}
