package com.healthanalyzer.app.ai

import com.healthanalyzer.app.data.Sample
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrZonesTest {

    private fun samples(vararg hr: Float) = hr.mapIndexed { i, h -> Sample(t = i * 1000L, hr = h, cadence = null, speed = null, power = null) }

    @Test
    fun customZonesUseBpmBoundaries() {
        // 10 s at 130 (Z2 with starts 120/140/155/170), 10 s at 160 (Z4).
        val s = samples(*FloatArray(11) { 130f }, *FloatArray(10) { 160f })
        val text = HrZones.timeInZones(s, listOf(120, 140, 155, 170), maxHr = null)!!
        assertTrue(text, text.contains("Z2 120–139 0:10 (50%)"))
        assertTrue(text, text.contains("Z4 155–169 0:10 (50%)"))
    }

    @Test
    fun percentOfMaxWithoutCustomZones() {
        val text = HrZones.timeInZones(samples(150f, 150f, 150f), null, maxHr = 200)!!
        assertTrue(text, text.contains("% of max HR 200"))
        assertTrue(text, text.contains("Z3 70–80% 0:02 (100%)"))
    }

    @Test
    fun noZonesWithoutMaxOrCustom() {
        assertNull(HrZones.timeInZones(samples(150f, 150f), null, maxHr = null))
    }
}
