package com.healthanalyzer.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSyncIntervalTest {
    @Test
    fun missingOrUnknownMeansDaily() {
        assertEquals(AutoSyncInterval.DAILY, AutoSyncInterval.fromName(null))
        assertEquals(AutoSyncInterval.DAILY, AutoSyncInterval.fromName("H1"))
        assertEquals(AutoSyncInterval.OFF, AutoSyncInterval.fromName("OFF"))
        assertEquals(6L, AutoSyncInterval.fromName("H6").hours)
    }

    @Test
    fun summaryListsEachSource() {
        val s = AutoSyncStatus(1, 1, listOf(SourceOutcome("Garmin", true, "1 new run(s)"), SourceOutcome("Health Connect", false, "denied")))
        assertEquals("Garmin: 1 new run(s) · Health Connect: denied", s.summary)
        assertEquals("No connected source to sync", AutoSyncStatus().summary)
    }
}
