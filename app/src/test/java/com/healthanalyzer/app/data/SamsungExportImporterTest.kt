package com.healthanalyzer.app.data

import com.google.gson.JsonParser
import com.healthanalyzer.app.samsung.SyncedRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SamsungExportImporterTest {

    @Test
    fun csvHandlesMetadataLineQuotesAndNewlines() {
        val text = "﻿com.samsung.shealth.exercise,1,3\na,b,c\n1,\"x, \"\"y\"\"\nz\",3\n4,5,\n"
        val rows = SamsungExportImporter.readCsv(text)
        assertEquals(2, rows.size)
        assertEquals("x, \"y\"\nz", rows[0]["b"])
        assertEquals("", rows[1]["c"])
    }

    @Test
    fun parsesTimesAndOffsets() {
        assertEquals(1793567942017L, SamsungExportImporter.parseUtc("2026-11-01 21:19:02.017"))
        assertEquals(-18000, SamsungExportImporter.parseOffset("UTC-0500"))
        assertEquals(19800, SamsungExportImporter.parseOffset("UTC+0530"))
    }

    @Test
    fun averagesMyotestMetricsSkippingGaps() {
        val json = JsonParser.parseString(
            """{"data":[{"service_name":"Myotest","advanced_metrics":[
               {"data_type":"effective_contact_time","chart_data":[{"value":0.0},{"value":150.0},{"value":160.0}]},
               {"data_type":"asymmetry","chart_data":[{"value":-0.02},{"value":0.04}]}]}]}""",
        )
        val d = SamsungExportImporter.dynamics(json)
        assertEquals(155f, d.getValue("effective_contact_time"), 0.01f)
        assertEquals(0.03f, d.getValue("asymmetry"), 0.0001f)
    }

    /** Runs against a real export when SAMSUNG_EXPORT_DIR points at a samsunghealth_… folder. */
    @Test
    fun readsRealExportIfPresent() = runBlocking {
        val dir = System.getenv("SAMSUNG_EXPORT_DIR")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue(dir != null)
        val runs = mutableListOf<SyncedRun>()
        val result = SamsungExportImporter.parse(DirectorySamsungExport(dir!!)) { runs += it }
        println("export: $result")
        assertTrue(result.runsFound > 0)
        val withRoute = runs.first { it.route.isNotEmpty() }
        assertTrue(withRoute.samples.isNotEmpty())
        val dyn = runs.first { it.run.groundContactMs != null }.run
        println("sample run with dynamics: $dyn")
        assertNotNull(dyn.flightTimeMs)
        assertTrue(dyn.groundContactMs!! in 100f..400f)
        assertTrue(dyn.verticalOscillationMm!! in 20f..200f)
    }
}
