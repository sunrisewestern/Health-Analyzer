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
        assertTrue(text, text.contains("runs planned 2 (7.0 km) · runs and walks recorded 2 (10.2 km) · 1 of 2 planned run days"))
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

    private fun other(day: LocalDate, type: String, minutes: Int, hr: String? = null, app: String? = null, km: Float? = null) =
        com.healthanalyzer.app.data.OtherWorkoutEntity(
            id = "$day-$type-$minutes", exerciseType = type, title = null,
            startEpochMs = day.atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endEpochMs = day.atTime(18, 0).plusMinutes(minutes.toLong()).toInstant(ZoneOffset.UTC).toEpochMilli(),
            zoneOffsetSec = 0, durationSec = minutes * 60L, distanceM = km?.times(1000), calories = 250f,
            meanHeartRate = 110f, maxHeartRate = 150f, altitudeGain = null, sourceAppId = app, hrSeconds = hr,
        )

    @Test fun otherTrainingIsListedAndCountsTowardWeeklyLoad() {
        val strength = other(today.minusDays(1), "OTHER", 50, hr = "100:1200,150:600", app = "com.hevy")
        val hike = other(today.minusDays(9), "HIKING", 120, km = 8.2f)
        val stroll = other(today.minusDays(1), "WALKING", 8)
        val walk = other(today.minusDays(8), "WALKING", 40, km = 3f)
        val list = TrainingLoad.otherTraining(listOf(strength, hike, stroll, walk), 28, today.minusDays(28))
        println(list)
        assertTrue(list, list.contains("Walks: 1 sessions, 40:00 in total, 3.0 km, avg HR 110"))
        assertTrue(list, list.contains("| Strength training | 50:00 | – | 110 | 150 | 250"))
        assertTrue(list, list.contains("| Hike | 2:00:00 | 8.2 |"))

        val zones = HrZones.zones(null, 188)!!
        // 100 bpm is Z1 (<113), 150 bpm is Z3 (132–150): 20 min Z1 + 10 min Z3 = load 50.
        val weekly = TrainingLoad.weekly(emptyList(), 2, zones, emptyMap(), today, listOf(strength, hike, stroll, walk))
        println(weekly)
        assertTrue(weekly, weekly.contains("| other sessions | other time"))
        assertTrue(weekly, weekly.contains("2026-11-02 | 0 | 0.0 | 0:00 | – | – | – | 1 | 3.0 | 40:00 | 1 | 2:00:00"))
        // The hike and the walk have no heart-rate rows: their whole time counts at the average (110 bpm, Z1).
        assertTrue(weekly, weekly.contains("2026-11-02 | 160 | 0 | 0 | 0 | 0 | 160 | – | 2 of 2"))
        assertTrue(weekly, weekly.contains("2026-11-09 | 20 | 0 | 10 | 0 | 0 | 50 | -69% | 1 of 1"))
    }

    @Test fun planReviewShowsOtherTrainingOnItsDay() {
        val plan = listOf(planned(today.minusDays(2), 3.5f))
        val text = TrainingLoad.planReview(
            plan, emptyList(), 4, today = today,
            others = listOf(other(today.minusDays(1), "OTHER", 50, app = "com.hevy")),
        )
        assertTrue(text, text.contains("2026-11-11 Wed | nothing planned | Strength training 50:00, avg HR 110"))
    }

    private fun day(d: LocalDate, score: Int?, minutes: Int?, energy: Float?, rhr: Int?) =
        com.healthanalyzer.app.data.DailyRecoveryEntity(
            date = d.toString(), sleepScore = score, sleepMinutes = minutes, deepMinutes = 60, remMinutes = 90, awakeMinutes = 30,
            energyScore = energy, restingHr = rhr,
        )

    @Test fun recoveryListsDaysAndComparesTheLastWeek() {
        val rows = (0L..30L).map { i ->
            if (i < 7) day(today.minusDays(i), 70, 390, 65f, 55) else day(today.minusDays(i), 80, 450, 80f, 51)
        }
        val text = TrainingLoad.recovery(rows, 7, today)
        println(text)
        assertEquals(7, text.lines().count { it.startsWith("2026-11-") })
        assertTrue(text, text.contains("2026-11-12 Thu | 70 | 6:30 | 1:00 | 1:30 | 0:30 | – | 65 | 55"))
        assertTrue(text, text.contains("sleep score 70 (7 days) vs 80 (24 days)"))
        assertTrue(text, text.contains("time asleep 6:30 (7 days) vs 7:30 (24 days)"))
        assertTrue(text, text.contains("resting HR 55 bpm (7 days) vs 51 bpm (24 days)"))
        assertTrue(TrainingLoad.recovery(emptyList(), 7, today).contains("no sleep or recovery data"))
    }

    @Test fun recoveryForOneRunDay() {
        val rows = (1L..10L).map { day(today.minusDays(it), 80, 450, 80f, 51) } + day(today, 62, 330, null, 56)
        val line = TrainingLoad.recoveryOn(today, rows)!!
        assertEquals(
            "Recovery that day: slept 5:30 (sleep score 62), resting HR 56 bpm. Average of the 4 weeks before: sleep score 80, energy score 80, resting HR 51 bpm",
            line,
        )
        assertNull(TrainingLoad.recoveryOn(today.plusDays(1), rows))
    }

    @Test fun heartRateHistogramSkipsPausesAndRoundTrips() {
        // 1 Hz for 60 s at 120 bpm, a 5-minute pause, then 30 s at 140 bpm.
        val readings = (0..60).map { it * 1000L to 120f } + (0..30).map { (360 + it) * 1000L to 140f }
        val text = com.healthanalyzer.app.data.HrHistogram.encode(readings)!!
        assertEquals(mapOf(120 to 60.0, 140 to 30.0), com.healthanalyzer.app.data.HrHistogram.decode(text))
        // A sparse log (one reading a minute) still counts its full time.
        val sparse = (0..10).map { it * 60_000L to 100f }
        assertEquals(mapOf(100 to 600.0), com.healthanalyzer.app.data.HrHistogram.decode(com.healthanalyzer.app.data.HrHistogram.encode(sparse)))
    }

    @Test fun reviewTemplateGetsRecoveryPlaceholderOnce() {
        val upgraded = withRecovery("a\n{{recent_runs:28}}\n\n{{plan_review:4}}")!!
        assertEquals("a\n{{recent_runs:28}}\n\n{{recovery:14}}\n\n{{plan_review:4}}", upgraded)
        assertNull(withRecovery(upgraded))
    }

    @Test
    fun `built-in planning requests become the planning placeholder`() {
        val oldRun = "Cover: cadence, how this run fits my\n    recent load, and 2–3 concrete suggestions for upcoming runs."
        assertEquals("Cover: cadence, and how this run fits my recent load.\n\n{{planning}}", withPlanningPlaceholder(oldRun))
        val oldReview = "VO2max trend. Then\npropose a plan for the next 7 days with distances and target heart rates or paces."
        assertEquals("VO2max trend.\n\n{{planning}}", withPlanningPlaceholder(oldReview))
        assertNull(withPlanningPlaceholder("Plan my week around a Friday dinner."))
        DEFAULT_PROMPTS.forEach {
            assertNull(it.name, withPlanningPlaceholder(it.systemPrompt))
            assertNull(it.name, withPlanningPlaceholder(it.userTemplate))
        }
        assertEquals(2, DEFAULT_PROMPTS.count { PlanningAdvice.PLACEHOLDER in it.userTemplate })
    }
}
