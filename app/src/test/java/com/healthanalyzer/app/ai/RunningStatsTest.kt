package com.healthanalyzer.app.ai

import com.healthanalyzer.app.data.RunEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class RunningStatsTest {
    private val today = LocalDate.of(2026, 11, 7)

    private fun run(day: LocalDate, km: Double, minutes: Int, hr: Float?, type: String = "RUNNING", vo2: Float? = null) = RunEntity(
        id = day.toString() + km, dataPointUid = "u", exerciseType = type,
        startEpochMs = day.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        endEpochMs = day.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), zoneOffsetSec = 0, title = null,
        durationSec = minutes * 60L, distanceM = (km * 1000).toFloat(), calories = 300f,
        meanHeartRate = hr, maxHeartRate = hr?.plus(20), minHeartRate = null, meanSpeed = null, maxSpeed = null,
        meanCadence = 165f, maxCadence = 180f, altitudeGain = 20f, altitudeLoss = null, maxAltitude = null,
        minAltitude = null, meanPower = null, maxPower = null, vo2Max = vo2, stepCount = null,
        sampleCount = 0, routePointCount = 0, sourceAppId = null, sourceDeviceId = null, updatedEpochMs = null,
    )

    @Test fun summarizesBestsEasyRunsAndVo2Trend() {
        val runs = listOf(
            run(today.minusDays(60), 5.0, 35, 130f, vo2 = 41.8f),
            run(today.minusDays(30), 16.09, 113, 145f, vo2 = 43.3f),
            run(today.minusDays(6), 6.89, 41, 142f, vo2 = 44.8f),
            run(today.minusDays(3), 4.8, 60, 135f, type = "TREADMILL"),
        )
        val text = RunningStats.build(runs, 12, maxHr = 184, today = today)
        println(text)
        assertTrue(text.contains("Runs: 4 (outdoor 3, treadmill 1, track 0)"))
        assertTrue(text.contains("Longest run: ${today.minusDays(30)}, 16.09 km"))
        assertTrue(text.contains("Fastest average pace over ≥5 km: ${today.minusDays(6)}"))
        assertTrue(text.contains("VO2max estimate: 41.8"))
        assertTrue(text.contains("→ 44.8"))
        assertTrue(text.contains("Typical easy run"))
    }

    @Test fun emptyHistory() {
        assertTrue(RunningStats.build(emptyList(), 12, null, today).contains("no runs"))
    }
}
