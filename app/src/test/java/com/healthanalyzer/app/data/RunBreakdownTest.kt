package com.healthanalyzer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunBreakdownTest {

    /** 1 Hz rows at 2.5 m/s: [firstSec] s of running, a [pauseSec] s pause, then [secondSec] s more. */
    private fun samples(firstSec: Int, pauseSec: Int, secondSec: Int): List<Sample> {
        val out = mutableListOf<Sample>()
        fun run(from: Int, n: Int, hr: Float) = (0..n).forEach { i ->
            val t = (from + i) * 1000L
            out += Sample(t = t, hr = hr, cadence = null, speed = null, power = null)
            out += Sample(t = t + 400, hr = null, cadence = 170f, speed = 2.5f, power = null)
        }
        run(0, firstSec, 150f)
        run(firstSec + pauseSec, secondSec, 120f)
        return out
    }

    @Test fun pauseIsLeftOutOfSplitTimeAndListed() {
        val b = RunAnalysis.breakdown(samples(firstSec = 200, pauseSec = 60, secondSec = 400))
        assertEquals(2, b.splits.size)
        val first = b.splits[0]
        assertEquals(1000.0, first.distanceM, 3.0)
        assertEquals(400.0, first.durationSec, 2.0)
        assertEquals(400.0, first.paceSecPerKm, 2.0)
        assertEquals(60.0, first.pausedSec, 0.01)
        assertEquals(150f, first.maxHeartRate!!, 0f)
        assertEquals(0.88, first.stepLengthM!!, 0.01)
        assertEquals(0.0, b.splits[1].pausedSec, 0.0)

        val pause = b.pauses.single()
        assertEquals(500.0, pause.atDistanceM, 3.0)
        assertEquals(60.0, pause.durationSec, 0.01)
        assertEquals(150f, pause.hrBefore!!, 0f)
        assertEquals(120f, pause.hrAfter!!, 0f)
    }

    @Test fun elevationIsSplitPerKmAndIgnoresJitter() {
        val s = samples(firstSec = 400, pauseSec = 0, secondSec = 400)
        // Climb 20 m over the first km with ±0.1 m jitter, then drop 8 m over the second.
        val route = (0..800).map { i ->
            val alt = if (i <= 400) 100 + i * 0.05 else 120 - (i - 400) * 0.02
            RoutePoint(t = i * 1000L + 400, lat = 0f, lon = 0f, alt = (alt + if (i % 2 == 0) 0.1 else -0.1).toFloat(), acc = 3f)
        }
        val splits = RunAnalysis.breakdown(s, route).splits
        assertEquals(20.0, splits[0].elevationGainM!!, 1.0)
        assertEquals(0.0, splits[0].elevationLossM!!, 0.6)
        assertEquals(0.0, splits[1].elevationGainM!!, 0.6)
        assertEquals(8.0, splits[1].elevationLossM!!, 1.0)
    }

    @Test fun noAltitudeMeansNoElevation() {
        assertNull(RunAnalysis.breakdown(samples(400, 0, 10)).splits.first().elevationGainM)
    }

    @Test fun halvesUseMovingTimeOnly() {
        val (a, b) = RunAnalysis.halves(samples(firstSec = 300, pauseSec = 600, secondSec = 300))!!
        assertEquals(400.0, a.paceSecPerKm, 1.0)
        assertEquals(400.0, b.paceSecPerKm, 1.0)
        assertEquals(150.0, a.meanHeartRate, 0.5)
        assertEquals(120.0, b.meanHeartRate, 0.5)
    }
}
