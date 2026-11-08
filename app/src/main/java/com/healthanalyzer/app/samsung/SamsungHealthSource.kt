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
import com.healthanalyzer.app.data.HrReading
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

    /** Shows Samsung Health's permission screen. Returns true if everything was granted. */
    suspend fun requestPermissions(activity: Activity): Boolean =
        store.requestPermissions(permissions, activity).containsAll(permissions)

    /** Reads every running-type session that starts in [since, until). */
    suspend fun readRuns(since: Instant, until: Instant): List<SyncedRun> {
        val result = mutableListOf<SyncedRun>()
        var pageToken: String? = null
        do {
            val builder = DataTypes.EXERCISE.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(since, until))
                .setOrdering(Ordering.ASC)
                // Sessions carry per-second logs and GPS routes, so keep pages small.
                .setPageSize(PAGE_SIZE)
            pageToken?.let { builder.setPageToken(it) }

            val response = store.readData(builder.build())
            response.dataList.forEach { point -> result += toRuns(point) }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return result
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
        val readings = mutableListOf<HrReading>()
        var pageToken: String? = null
        do {
            val builder = DataTypes.HEART_RATE.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(today.minusDays(days.toLong()), today))
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
