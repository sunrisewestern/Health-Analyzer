package com.healthanalyzer.app.data

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** One heart-rate reading in the athlete's local time. */
data class HrReading(val time: LocalDateTime, val bpm: Float)

data class RestingHrEstimate(val bpm: Int, val days: Int)

/**
 * Estimates resting heart rate from all-day heart-rate readings. Pure JVM.
 *
 * For each day it takes the lowest average over a sustained [windowMinutes] window (so a single
 * low reading or a sensor dropout can't drag it down), then returns the median over the days.
 * A day's daily *minimum* — what the app used before — is a one-off low, typically several bpm
 * below the resting HR Samsung Health shows.
 */
object RestingHeartRate {

    fun estimate(
        readings: List<HrReading>,
        windowMinutes: Long = 30,
        minReadingsPerWindow: Int = 3,
    ): RestingHrEstimate? {
        val daily = readings
            .filter { it.bpm in 25f..220f }
            .groupBy { it.time.toLocalDate() }
            .mapNotNull { (_, day) -> lowestWindowMean(day.sortedBy { it.time }, windowMinutes, minReadingsPerWindow) }
            .sorted()
        if (daily.isEmpty()) return null
        val mid = daily.size / 2
        val median = if (daily.size % 2 == 1) daily[mid] else (daily[mid - 1] + daily[mid]) / 2
        return RestingHrEstimate(Math.round(median).toInt(), daily.size)
    }

    /** Lowest mean over any [windowMinutes]-long window that starts at a reading and is at least 2/3 covered. */
    private fun lowestWindowMean(day: List<HrReading>, windowMinutes: Long, minCount: Int): Double? {
        var best: Double? = null
        var end = 0
        var sum = 0.0
        for (start in day.indices) {
            val limit = day[start].time.plus(windowMinutes, ChronoUnit.MINUTES)
            while (end < day.size && day[end].time < limit) { sum += day[end].bpm; end++ }
            val count = end - start
            // The window must really be covered, not just a few readings at the end of the day.
            val spanMin = ChronoUnit.MINUTES.between(day[start].time, day[end - 1].time)
            if (count >= minCount && spanMin * 3 >= windowMinutes * 2) {
                val mean = sum / count
                if (best == null || mean < best) best = mean
            }
            sum -= day[start].bpm
        }
        return best
    }
}
