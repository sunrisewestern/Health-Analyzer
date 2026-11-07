package com.healthanalyzer.app.data

import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FitImporterTest {
    private val start: Instant = Instant.parse("2026-11-01T21:19:02Z")

    /** Writes a FIT activity with one 10-minute run (+ an optional bike ride) using Garmin's SDK. */
    private fun fitFile(withRide: Boolean = false, subSport: SubSport = SubSport.STREET): ByteArray {
        val file = File.createTempFile("activity", ".fit")
        val enc = FileEncoder(file, Fit.ProtocolVersion.V2_0)
        enc.write(FileIdMesg().apply {
            type = com.garmin.fit.File.ACTIVITY; manufacturer = 1; product = 4315; serialNumber = 3_456_789_012L
            timeCreated = DateTime(start)
        })
        val seconds = 600
        for (i in 0..seconds) {
            enc.write(RecordMesg().apply {
                timestamp = DateTime(start.plusSeconds(i.toLong()))
                heartRate = (130 + i / 30).toShort()
                cadence = 82.toShort()                 // strides/min per leg -> 164 spm
                enhancedSpeed = 2.9f                   // m/s
                distance = 2.9f * i
                positionLat = (43.65 / (180.0 / 2147483648.0)).toInt() + i * 10
                positionLong = (-79.38 / (180.0 / 2147483648.0)).toInt()
                stanceTime = 250f
                verticalOscillation = 88f
            })
        }
        enc.write(SessionMesg().apply {
            startTime = DateTime(start); timestamp = DateTime(start.plusSeconds(seconds.toLong()))
            sport = Sport.RUNNING; this.subSport = subSport
            totalTimerTime = seconds.toFloat(); totalElapsedTime = seconds.toFloat() + 30f
            totalDistance = 1740f; totalCalories = 160
            avgHeartRate = 140.toShort(); maxHeartRate = 150.toShort()
            avgRunningCadence = 82.toShort(); avgFractionalCadence = 0.5f; maxRunningCadence = 90.toShort()
            enhancedAvgSpeed = 2.9f; enhancedMaxSpeed = 3.4f; totalAscent = 12; totalDescent = 10
            avgStanceTime = 251f; avgVerticalOscillation = 87.5f; avgVerticalRatio = 8.4f; avgStepLength = 1060f
            avgStanceTimeBalance = 49.6f; totalTrainingEffect = 2.7f; totalAnaerobicTrainingEffect = 0.4f
            totalStrides = 820L
        })
        if (withRide) {
            enc.write(SessionMesg().apply {
                startTime = DateTime(start.plusSeconds(3600)); sport = Sport.CYCLING
                totalTimerTime = 1200f; totalDistance = 9000f
            })
        }
        enc.write(ActivityMesg().apply {
            timestamp = DateTime(start.plusSeconds(seconds.toLong()))
            localTimestamp = DateTime(start.plusSeconds(seconds.toLong())).timestamp - 5 * 3600 // UTC-5
        })
        enc.close()
        return file.readBytes().also { file.delete() }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    @Test fun readsRunSummarySamplesRouteAndDynamics() {
        val result = FitImporter.parseFiles(listOf("morning.fit" to fitFile(withRide = true)))
        assertEquals(1, result.filesRead)
        assertEquals(1, result.skippedNonRunning)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        val synced = result.runs.single()
        val r = synced.run
        assertEquals(SOURCE_GARMIN_FIT, r.source)
        assertEquals("RUNNING", r.exerciseType)
        assertEquals(start.toEpochMilli(), r.startEpochMs)
        assertEquals(600L, r.durationSec)                    // moving time, not elapsed
        assertEquals(1740f, r.distanceM!!, 0.01f)
        assertEquals(140f, r.meanHeartRate!!, 0f)
        assertEquals(165f, r.meanCadence!!, 0.01f)           // (82 + 0.5) * 2 steps/min
        assertEquals(1640, r.stepCount)
        assertEquals(251f, r.groundContactMs!!, 0f)
        assertEquals(87.5f, r.verticalOscillationMm!!, 0f)
        assertEquals(1060f, r.stepLengthMm!!, 0f)
        assertEquals(2.7f, r.aerobicTrainingEffect!!, 0.001f)
        assertEquals(-5 * 3600, r.zoneOffsetSec)
        assertEquals(601, synced.samples.size)
        assertEquals(164f, synced.samples.first().cadence!!, 0f)
        assertEquals(250f, synced.samples.first().gct!!, 0f)
        assertEquals(601, synced.route.size)
        assertEquals(43.65f, synced.route.first().lat, 0.0001f)
        assertEquals(-79.38f, synced.route.first().lon, 0.0001f)
        assertTrue(r.sourceDetail!!.contains("Garmin") && r.sourceDetail!!.contains("morning.fit"))
    }

    @Test fun splitsWorkOnImportedSamples() {
        val synced = FitImporter.parseFiles(listOf("a.fit" to fitFile())).runs.single()
        val splits = RunAnalysis.splits(synced.samples, synced.run.distanceM)
        assertEquals(1, splits.count { it.distanceM >= 999 })
        assertEquals(1000 / 2.9, splits.first().paceSecPerKm, 3.0)
    }

    @Test fun treadmillSubSportMapsToTreadmill() {
        val r = FitImporter.parseFiles(listOf("t.fit" to fitFile(subSport = SubSport.TREADMILL))).runs.single().run
        assertEquals("TREADMILL", r.exerciseType)
    }

    @Test fun readsNestedGarminExportZipAndReportsBadFiles() {
        val inner = zip("UploadedFiles_0-_Part1/3456_ACTIVITY.fit" to fitFile())
        val outer = zip(
            "DI_CONNECT/DI-Connect-Uploaded-Files/UploadedFiles_0-_Part1.zip" to inner,
            "DI_CONNECT/readme.txt" to "ignored".toByteArray(),
        )
        val result = FitImporter.parseFiles(listOf("garmin_export.zip" to outer, "notes.txt" to "x".toByteArray()))
        assertEquals(1, result.runs.size)
        assertEquals(1, result.failures.size)
        assertNotNull(result.runs.single().run.id)
    }

    @Test fun reimportingSameFileGivesSameIds() {
        val bytes = fitFile()
        val a = FitImporter.parseFiles(listOf("a.fit" to bytes)).runs.single().run.id
        val b = FitImporter.parseFiles(listOf("renamed.fit" to bytes)).runs.single().run.id
        assertEquals(a, b)
    }
}
