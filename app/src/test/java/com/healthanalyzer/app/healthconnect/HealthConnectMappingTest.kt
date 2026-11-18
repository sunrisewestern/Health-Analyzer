package com.healthanalyzer.app.healthconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HealthConnectMappingTest {

    private val night = Instant.parse("2026-11-15T22:00:00Z")
    private fun at(minutes: Long) = night.plusSeconds(minutes * 60)
    private fun stage(from: Long, to: Long, type: Int) = SleepStage(at(from), at(to), type)

    @Test
    fun `running types use the names the rest of the app knows`() {
        assertTrue(HealthConnectMapping.isRun(56))
        assertTrue(HealthConnectMapping.isRun(57))
        assertFalse(HealthConnectMapping.isRun(70))
        assertEquals("RUNNING", HealthConnectMapping.typeName(56))
        assertEquals("TREADMILL", HealthConnectMapping.typeName(57))
        assertEquals("STRENGTH_TRAINING", HealthConnectMapping.typeName(70))
        assertEquals("WALKING", HealthConnectMapping.typeName(79))
        assertEquals("OTHER", HealthConnectMapping.typeName(0))
        assertEquals("OTHER", HealthConnectMapping.typeName(999))
    }

    @Test
    fun `series are joined per timestamp, oldest first`() {
        val samples = HealthConnectMapping.mergeSeries(
            hr = listOf(2000L to 150f, 1000L to 140f),
            speed = listOf(1000L to 3.1f),
            cadence = listOf(3000L to 172f),
            power = emptyList(),
        )
        assertEquals(listOf(1000L, 2000L, 3000L), samples.map { it.t })
        assertEquals(140f, samples[0].hr)
        assertEquals(3.1f, samples[0].speed)
        assertNull(samples[1].speed)
        assertNull(samples[2].hr)
        assertEquals(172f, samples[2].cadence)
        assertNull(samples[0].power)
    }

    @Test
    fun `asleep time comes from the stages and leaves out time awake`() {
        val sleep = SleepSpan(
            at(0), at(480),
            listOf(
                stage(0, 30, HealthConnectMapping.STAGE_AWAKE_IN_BED),
                stage(30, 240, HealthConnectMapping.STAGE_LIGHT),
                stage(240, 330, HealthConnectMapping.STAGE_DEEP),
                stage(330, 345, HealthConnectMapping.STAGE_AWAKE),
                stage(345, 450, HealthConnectMapping.STAGE_REM),
                stage(450, 480, HealthConnectMapping.STAGE_OUT_OF_BED),
            ),
        )
        assertEquals(210 + 90 + 105, HealthConnectMapping.asleepMinutes(sleep))
        assertEquals(90, HealthConnectMapping.deepMinutes(sleep))
        assertEquals(105, HealthConnectMapping.remMinutes(sleep))
        assertEquals(45, HealthConnectMapping.awakeMinutes(sleep))
    }

    @Test
    fun `a sleep without stages counts in full and has no stage breakdown`() {
        val sleep = SleepSpan(at(0), at(420))
        assertEquals(420, HealthConnectMapping.asleepMinutes(sleep))
        assertNull(HealthConnectMapping.deepMinutes(sleep))
        assertNull(HealthConnectMapping.awakeMinutes(sleep))
    }

    @Test
    fun `the same night written by two apps is kept once, a nap stays`() {
        val watch = SleepSpan(at(0), at(480))
        val phone = SleepSpan(at(20), at(450))
        val nap = SleepSpan(at(900), at(930))
        assertEquals(setOf(watch, nap), HealthConnectMapping.withoutOverlaps(listOf(phone, nap, watch)).toSet())
    }
}
