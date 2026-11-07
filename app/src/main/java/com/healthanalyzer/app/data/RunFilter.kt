package com.healthanalyzer.app.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

enum class RunTypeFilter(val label: String, val types: Set<String>?) {
    ALL("All", null),
    OUTDOOR("Outdoor", setOf("RUNNING")),
    TREADMILL("Treadmill", setOf("TREADMILL")),
    TRACK("Track", setOf("TRACK_RUNNING")),
}

enum class RangeFilter(val label: String, val days: Long?) {
    WEEKS_4("4W", 28),
    MONTHS_3("3M", 91),
    MONTHS_6("6M", 182),
    YEAR("1Y", 365),
    ALL("All", null),
}

enum class SourceFilter(val label: String, val source: String?) {
    ALL("All sources", null),
    SAMSUNG("Samsung", SOURCE_SAMSUNG),
    GARMIN("Garmin", SOURCE_GARMIN_FIT),
}

enum class DistanceFilter(val label: String, val minKm: Double) {
    ANY("Any", 0.0),
    KM_5("≥5 km", 5.0),
    KM_10("≥10 km", 10.0),
    KM_21("≥21 km", 21.0),
}

data class RunFilter(
    val type: RunTypeFilter = RunTypeFilter.ALL,
    val range: RangeFilter = RangeFilter.ALL,
    val distance: DistanceFilter = DistanceFilter.ANY,
    val source: SourceFilter = SourceFilter.ALL,
) {
    val isDefault get() = this == RunFilter()

    fun apply(runs: List<RunEntity>, today: LocalDate = LocalDate.now()): List<RunEntity> {
        val since = range.days?.let { today.minusDays(it - 1) }
        return runs.filter { r ->
            (type.types == null || r.exerciseType in type.types) &&
                (since == null || !localDate(r).isBefore(since)) &&
                (r.distanceM ?: 0f) / 1000.0 >= distance.minKm &&
                (source.source == null || r.sourceOrDefault == source.source)
        }
    }
}

/** Totals and series for the dashboard, computed from already-filtered runs. */
data class RunSummary(
    val count: Int,
    val distanceKm: Double,
    val durationSec: Long,
    val avgPaceSecPerKm: Double?,
    val avgHeartRate: Double?,
    val longestKm: Double,
    /** (bucket start, km) – weekly, or monthly for long ranges. */
    val volume: List<Pair<LocalDate, Double>>,
    val monthlyBuckets: Boolean,
    /** (run date, value) series in time order. */
    val pace: List<Pair<LocalDate, Double>>,
    val heartRate: List<Pair<LocalDate, Double>>,
    val vo2Max: List<Pair<LocalDate, Double>>,
) {
    companion object {
        fun of(runs: List<RunEntity>, today: LocalDate = LocalDate.now(), rangeDays: Long?): RunSummary {
            val sorted = runs.sortedBy { it.startEpochMs }
            val km = sorted.sumOf { (it.distanceM ?: 0f) / 1000.0 }
            val time = sorted.sumOf { it.durationSec }
            val hrRuns = sorted.filter { (it.meanHeartRate ?: 0f) > 0f }
            val hrTime = hrRuns.sumOf { it.durationSec }

            val first = sorted.firstOrNull()?.let(::localDate) ?: today
            val spanDays = rangeDays ?: (today.toEpochDay() - first.toEpochDay() + 1)
            val monthly = spanDays > 200
            val bucketOf: (LocalDate) -> LocalDate =
                if (monthly) { d -> d.withDayOfMonth(1) } else { d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            val start = bucketOf(rangeDays?.let { today.minusDays(it - 1) } ?: first)
            val byBucket = sorted.groupBy { bucketOf(localDate(it)) }
            val volume = generateSequence(start) { if (monthly) it.plusMonths(1) else it.plusWeeks(1) }
                .takeWhile { !it.isAfter(today) }
                .map { b -> b to (byBucket[b]?.sumOf { (it.distanceM ?: 0f) / 1000.0 } ?: 0.0) }
                .toList()

            return RunSummary(
                count = sorted.size,
                distanceKm = km,
                durationSec = time,
                avgPaceSecPerKm = if (km > 0) time / km else null,
                avgHeartRate = if (hrTime > 0) hrRuns.sumOf { it.meanHeartRate!!.toDouble() * it.durationSec } / hrTime else null,
                longestKm = sorted.maxOfOrNull { (it.distanceM ?: 0f) / 1000.0 } ?: 0.0,
                volume = volume,
                monthlyBuckets = monthly,
                pace = sorted.filter { (it.distanceM ?: 0f) >= 1000 }
                    .map { localDate(it) to it.durationSec / ((it.distanceM ?: 1f) / 1000.0) },
                heartRate = hrRuns.map { localDate(it) to it.meanHeartRate!!.toDouble() },
                vo2Max = sorted.filter { it.vo2Max != null }.map { localDate(it) to it.vo2Max!!.toDouble() },
            )
        }
    }
}

internal fun localDate(r: RunEntity): LocalDate = Instant.ofEpochMilli(r.startEpochMs)
    .atZone(r.zoneOffsetSec?.let { ZoneOffset.ofTotalSeconds(it) } ?: ZoneId.systemDefault())
    .toLocalDate()
