package com.healthanalyzer.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class RunFilterTest {
    private val today = LocalDate.of(2026, 11, 7) // a Saturday

    private fun run(daysAgo: Long, km: Double, type: String = "RUNNING", minutes: Int = 30, hr: Float? = 140f) = RunEntity(
        id = "$daysAgo-$km-$type", dataPointUid = "u", exerciseType = type,
        startEpochMs = today.minusDays(daysAgo).atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        endEpochMs = 0, zoneOffsetSec = 0, title = null, durationSec = minutes * 60L,
        distanceM = (km * 1000).toFloat(), calories = 0f, meanHeartRate = hr, maxHeartRate = null, minHeartRate = null,
        meanSpeed = null, maxSpeed = null, meanCadence = null, maxCadence = null, altitudeGain = null, altitudeLoss = null,
        maxAltitude = null, minAltitude = null, meanPower = null, maxPower = null, vo2Max = null, stepCount = null,
        sampleCount = 0, routePointCount = 0, sourceAppId = null, sourceDeviceId = null, updatedEpochMs = null,
    )

    private val runs = listOf(
        run(1, 6.0), run(3, 4.8, "TREADMILL"), run(20, 12.0), run(100, 21.1), run(400, 5.0),
    )

    @Test fun typeFilter() {
        assertEquals(1, RunFilter(type = RunTypeFilter.TREADMILL).apply(runs, today).size)
        assertEquals(4, RunFilter(type = RunTypeFilter.OUTDOOR).apply(runs, today).size)
    }

    @Test fun rangeFilterIncludesTodayAndBoundary() {
        assertEquals(3, RunFilter(range = RangeFilter.WEEKS_4).apply(runs, today).size)
        assertEquals(4, RunFilter(range = RangeFilter.YEAR).apply(runs, today).size)
        assertEquals(5, RunFilter().apply(runs, today).size)
    }

    @Test fun distanceFilter() {
        assertEquals(2, RunFilter(distance = DistanceFilter.KM_10).apply(runs, today).size)
        assertEquals(1, RunFilter(distance = DistanceFilter.KM_21).apply(runs, today).size)
    }

    @Test fun weeklyVolumeBucketsCoverRangeWithZeros() {
        val r = RunFilter(range = RangeFilter.WEEKS_4).apply(runs, today)
        val s = RunSummary.of(r, today, rangeDays = 28)
        assertEquals(false, s.monthlyBuckets)
        assertEquals(LocalDate.of(2026, 10, 5), s.volume.first().first) // Monday on/before Oct 11
        assertEquals(10.8, s.volume.last().second, 1e-6)                // this week: 6.0 + 4.8
        assertEquals(22.8, s.distanceKm, 1e-6)
        assertEquals(12.0, s.longestKm, 1e-6)
    }

    @Test fun longRangesUseMonthlyBuckets() {
        val s = RunSummary.of(runs, today, rangeDays = null)
        assertEquals(true, s.monthlyBuckets)
        assertEquals(1, s.volume.first().first.dayOfMonth)
    }
}
