package com.healthanalyzer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class RestingHeartRateTest {

    private fun day(date: Int, vararg minuteBpm: Pair<Int, Float>) =
        minuteBpm.map { (m, bpm) -> HrReading(LocalDateTime.of(2026, 11, date, 0, 0).plusMinutes(m.toLong()), bpm) }

    @Test
    fun singleLowReadingDoesNotSetRestingHr() {
        // 52 bpm every minute for two hours, with one 41 bpm blip in the middle.
        val readings = day(1, *(0..120).map { if (it == 60) it to 41f else it to 52f }.toTypedArray())
        assertEquals(52, RestingHeartRate.estimate(readings)!!.bpm)
    }

    @Test
    fun takesLowestSustainedWindowPerDayAndMedianAcrossDays() {
        val readings = day(1, 0 to 70f, 10 to 70f, 20 to 70f, 300 to 50f, 310 to 50f, 320 to 50f) +
            day(2, 0 to 54f, 10 to 54f, 20 to 54f) +
            day(3, 0 to 60f, 10 to 60f, 20 to 60f)
        val est = RestingHeartRate.estimate(readings)!!
        assertEquals(54, est.bpm)
        assertEquals(3, est.days)
    }

    @Test
    fun tooFewReadingsGivesNothing() {
        assertNull(RestingHeartRate.estimate(day(1, 0 to 50f, 10 to 50f)))
    }
}
