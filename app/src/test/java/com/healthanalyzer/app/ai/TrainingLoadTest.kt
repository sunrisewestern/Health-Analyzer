package com.healthanalyzer.app.ai

import com.healthanalyzer.app.data.RunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TrainingLoadTest {
    /** A Thursday. */
    private val today = LocalDate.of(2026, 11, 12)

    private fun run(
        day: LocalDate, km: Double, minutes: Int, hr: Float? = 140f, type: String = "RUNNING",
        cadence: Float? = 165f, elapsedMin: Int = minutes,
    ) = RunEntity(
        id = "$day-$km", dataPointUid = "u", exerciseType = type,
        startEpochMs = day.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        endEpochMs = day.atTime(8, 0).plusMinutes(elapsedMin.toLong()).toInstant(ZoneOffset.UTC).toEpochMilli(),
        zoneOffsetSec = 0, title = null,
        durationSec = minutes * 60L, distanceM = (km * 1000).toFloat(), calories = 300f,
        meanHeartRate = hr, maxHeartRate = hr?.plus(20), minHeartRate = null, meanSpeed = null, maxSpeed = null,
        meanCadence = cadence, maxCadence = null, altitudeGain = 20f, altitudeLoss = null, maxAltitude = null,
        minAltitude = null, meanPower = null, maxPower = null, vo2Max = null, stepCount = null,
        sampleCount = 100, routePointCount = 0, sourceAppId = null, sourceDeviceId = null, updatedEpochMs = null,
    )

    private fun planned(day: LocalDate, km: Float, type: WorkoutType = WorkoutType.EASY) =
        PlanWorkoutEntity(date = day.toString(), type = type.name, title = "${type.short} $km km", distanceKm = km, target = "HR < 141 bpm")

    @Test fun walksAreToldApartByPaceOrCadence() {
        assertTrue(TrainingLoad.isWalk(run(today, 4.8, 60, type = "TREADMILL", cadence = null)))
        assertTrue(TrainingLoad.isWalk(run(today, 1.25, 10, cadence = 108f)))
        assertFalse(TrainingLoad.isWalk(run(today, 3.7, 23, type = "TREADMILL", cadence = null)))
        assertEquals("Treadmill walk", TrainingLoad.label(run(today, 4.8, 60, type = "TREADMILL", cadence = null)))
    }

    @Test fun weeklyKeepsWalksOutOfRunPaceAndAddsZoneLoad() {
        val walk = run(today.minusDays(8), 4.8, 60, hr = 135f, type = "TREADMILL", cadence = null)
        val lastWeek = run(today.minusDays(7), 5.0, 30)
        val thisWeek = run(today.minusDays(1), 10.0, 60)
        val zones = HrZones.zones(null, 188)!!
        val secs = mapOf(
            lastWeek.id to doubleArrayOf(0.0, 600.0, 1200.0, 0.0, 0.0), // 10 min Z2 + 20 min Z3 = load 80
            thisWeek.id to doubleArrayOf(0.0, 0.0, 2400.0, 1200.0, 0.0), // 40 min Z3 + 20 min Z4 = load 200
        )
        val text = TrainingLoad.weekly(listOf(walk, lastWeek, thisWeek), 2, zones, secs, today)
        println(text)
        assertTrue(text, text.contains("2026-11-02 | 1 | 5.0 | 30:00 | 6'00\" | 140 | 5.0 | 1 | 4.8 | 1:00:00"))
        assertTrue(text, text.contains("2026-11-02 | 0 | 10 | 20 | 0 | 0 | 80 | – | 1 of 2"))
        assertTrue(text, text.contains("2026-11-09 | 0 | 0 | 40 | 20 | 0 | 200 | +150% | 1 of 1"))
    }

    @Test fun recentRunsShowPausesAndRestDays() {
        val runs = listOf(
            run(today.minusDays(40), 5.0, 30),
            run(today.minusDays(7), 10.0, 59),
            run(today, 6.45, 38, elapsedMin = 48),
        )
        val text = TrainingLoad.recentRuns(runs, 28, today.minusDays(28))
        println(text)
        assertFalse(text.contains(today.minusDays(40).toString()))
        assertTrue(text, text.contains("10.00 | 59:00 | – |"))
        assertTrue(text, text.lines().first { it.startsWith(today.minusDays(7).toString()) }.endsWith("| 33"))
        assertTrue(text, text.contains("6.45 | 38:00 | 10:00 |"))
        assertTrue(text, text.lines().last().endsWith("| 7"))
    }

    @Test fun planReviewComparesPastDaysAndListsWhatIsNext() {
        val plan = listOf(
            planned(today.minusDays(3), 3.5f),
            planned(today.minusDays(1), 3.5f),
            planned(today.plusDays(1), 4.5f, WorkoutType.LONG),
            planned(today.plusDays(30), 5f),
        )
        val runs = listOf(run(today.minusDays(20), 8.0, 50), run(today.minusDays(3), 3.7, 23), run(today.minusDays(2), 6.45, 38))
        val text = TrainingLoad.planReview(plan, runs, weeks = 4, goal = "Sub-2:00 half", today = today)
        println(text)
        assertTrue(text, text.contains("Training plan goal: Sub-2:00 half"))
        assertTrue(text, text.contains("Plan vs actual, 2026-11-09 to 2026-11-11"))
        assertTrue(text, text.contains("2026-11-09 Mon | Easy run: Easy 3.5 km, 3.5 km, target HR < 141 bpm | Outdoor run 3.70 km in 23:00"))
        assertTrue(text, text.contains("2026-11-10 Tue | nothing planned | Outdoor run 6.45 km"))
        assertTrue(text, text.contains("2026-11-11 Wed | Easy run: Easy 3.5 km, 3.5 km, target HR < 141 bpm | nothing recorded"))
        assertTrue(text, text.contains("sessions planned 2 (7.0 km) · sessions recorded 2 (10.2 km) · 1 of 2 planned days"))
        assertTrue(text, text.contains("2026-11-13 Fri | Long run: Long 4.5 km"))
        assertFalse(text, text.contains(today.plusDays(30).toString()))
        assertFalse("runs from before the plan started are not compared", text.contains("8.00 km"))
    }

    @Test fun planReviewWithoutPlan() {
        assertTrue(TrainingLoad.planReview(emptyList(), emptyList(), 4, today = today).contains("no training plan"))
    }

    @Test fun reviewTemplateGetsPlanPlaceholderOnce() {
        val upgraded = withPlanReview("a\n{{recent_runs:28}}\n\nReview")!!
        assertEquals("a\n{{recent_runs:28}}\n\n{{plan_review:4}}\n\nReview", upgraded)
        assertNull(withPlanReview(upgraded))
        assertNull(withPlanReview("no placeholders here"))
    }
}
