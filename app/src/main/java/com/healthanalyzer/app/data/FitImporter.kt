package com.healthanalyzer.app.data

import com.garmin.fit.FitDecoder
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.healthanalyzer.app.samsung.SyncedRun
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/** Result of reading one or more files. */
data class FitParseResult(
    val runs: List<SyncedRun>,
    val filesRead: Int,
    /** Activities that weren't runs (cycling, swimming, walking…). */
    val skippedNonRunning: Int,
    /** Files that couldn't be decoded, with the reason. */
    val failures: List<String>,
)

/**
 * Reads Garmin FIT activity files – single .fit files, or zips of them such as the
 * Garmin Connect account export (which nests zips inside zips). Pure JVM: no Android APIs.
 */
object FitImporter {

    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0 // 2^31

    fun parseFiles(files: List<Pair<String, ByteArray>>): FitParseResult {
        val runs = mutableListOf<SyncedRun>()
        var read = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        fun visit(name: String, bytes: ByteArray, depth: Int) {
            when {
                isZip(bytes) && depth < 4 -> ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val lower = entry.name.lowercase()
                        if (lower.endsWith(".fit") || lower.endsWith(".zip")) {
                            visit(entry.name.substringAfterLast('/'), zip.readBytes(), depth + 1)
                        }
                    }
                }
                name.lowercase().endsWith(".fit") || looksLikeFit(bytes) -> {
                    read++
                    try {
                        val (found, nonRunning) = parseFit(ByteArrayInputStream(bytes), name)
                        runs += found
                        skipped += nonRunning
                    } catch (e: Exception) {
                        failures += "$name: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                else -> failures += "$name: not a .fit or .zip file"
            }
        }

        files.forEach { (name, bytes) -> visit(name, bytes, 0) }
        return FitParseResult(runs.distinctBy { it.run.id }, read, skipped, failures)
    }

    /** Returns the running sessions in one FIT file and the number of non-running ones. */
    fun parseFit(input: InputStream, fileName: String): Pair<List<SyncedRun>, Int> {
        val messages = FitDecoder().decode(input)
        val fileId = messages.fileIdMesgs.firstOrNull()
        val serial = fileId?.serialNumber ?: 0L
        val device = listOfNotNull(
            fileId?.manufacturer?.let { if (it == 1) "Garmin" else "manufacturer $it" },
            fileId?.product?.let { "product $it" },
        ).joinToString(" ").ifBlank { "FIT device" }

        // Local time offset: activity.local_timestamp (local seconds) minus timestamp (UTC seconds).
        val activity = messages.activityMesgs.firstOrNull()
        val zoneOffsetSec = activity?.let { a ->
            val local = a.localTimestamp ?: return@let null
            val utc = a.timestamp?.timestamp ?: return@let null
            (((local - utc) / 900.0).roundToInt() * 900).takeIf { kotlin.math.abs(it) <= 14 * 3600 }
        }

        val records = messages.recordMesgs.filter { it.timestamp != null }.sortedBy { it.timestamp.timestamp }
        var nonRunning = 0
        val runs = messages.sessionMesgs.mapIndexedNotNull { index, s ->
            if (s.sport != Sport.RUNNING) {
                nonRunning++
                return@mapIndexedNotNull null
            }
            val start = s.startTime ?: return@mapIndexedNotNull null
            val startMs = start.date.time
            val elapsedSec = s.totalElapsedTime?.toDouble() ?: s.totalTimerTime?.toDouble() ?: 0.0
            val endMs = startMs + (elapsedSec * 1000).toLong()
            val inSession = records.filter { r -> r.timestamp.date.time in startMs..endMs }
            toRun(s, index, inSession, fileName, serial, device, zoneOffsetSec, startMs, endMs)
        }
        return runs to nonRunning
    }

    private fun toRun(
        s: SessionMesg,
        index: Int,
        records: List<RecordMesg>,
        fileName: String,
        serial: Long,
        device: String,
        zoneOffsetSec: Int?,
        startMs: Long,
        endMs: Long,
    ): SyncedRun {
        val samples = records.map { r ->
            Sample(
                t = r.timestamp.date.time,
                hr = r.heartRate?.toFloat()?.takeIf { it > 0 },
                cadence = runningCadence(r.cadence, r.fractionalCadence),
                speed = r.enhancedSpeed ?: r.speed,
                power = r.power?.toFloat(),
                gct = r.stanceTime?.takeIf { it > 0 },
                vo = r.verticalOscillation?.takeIf { it > 0 },
            )
        }
        val route = records.mapNotNull { r ->
            val lat = r.positionLat ?: return@mapNotNull null
            val lon = r.positionLong ?: return@mapNotNull null
            RoutePoint(
                t = r.timestamp.date.time,
                lat = (lat * SEMICIRCLE_TO_DEG).toFloat(),
                lon = (lon * SEMICIRCLE_TO_DEG).toFloat(),
                alt = r.enhancedAltitude ?: r.altitude,
                acc = null,
            )
        }
        val type = when (s.subSport) {
            SubSport.TREADMILL, SubSport.INDOOR_RUNNING, SubSport.VIRTUAL_ACTIVITY -> "TREADMILL"
            SubSport.TRACK -> "TRACK_RUNNING"
            else -> "RUNNING"
        }
        val movingSec = (s.totalTimerTime ?: s.totalElapsedTime ?: 0f).toDouble()
        val hrs = samples.mapNotNull { it.hr }
        val steps = s.totalStrides?.let { it * 2 }?.toInt()

        val run = RunEntity(
            id = "fit:$serial:${startMs / 1000}:$index",
            dataPointUid = "fit:$serial:${startMs / 1000}",
            exerciseType = type,
            startEpochMs = startMs,
            endEpochMs = endMs,
            zoneOffsetSec = zoneOffsetSec,
            title = s.sportProfileName?.takeIf { it.isNotBlank() },
            durationSec = movingSec.roundToInt().toLong(),
            distanceM = s.totalDistance,
            calories = (s.totalCalories ?: 0).toFloat(),
            meanHeartRate = s.avgHeartRate?.toFloat()?.takeIf { it > 0 },
            maxHeartRate = s.maxHeartRate?.toFloat()?.takeIf { it > 0 },
            minHeartRate = s.minHeartRate?.toFloat()?.takeIf { it > 0 } ?: hrs.minOrNull(),
            meanSpeed = s.enhancedAvgSpeed ?: s.avgSpeed,
            maxSpeed = s.enhancedMaxSpeed,
            meanCadence = runningCadence(s.avgRunningCadence, s.avgFractionalCadence),
            maxCadence = runningCadence(s.maxRunningCadence, null),
            altitudeGain = s.totalAscent?.toFloat(),
            altitudeLoss = s.totalDescent?.toFloat(),
            maxAltitude = s.enhancedMaxAltitude,
            minAltitude = s.enhancedMinAltitude,
            meanPower = s.avgPower?.toFloat(),
            maxPower = s.maxPower?.toFloat(),
            vo2Max = null, // FIT sessions don't carry Garmin's VO2max estimate
            stepCount = steps,
            sampleCount = samples.size,
            routePointCount = route.size,
            sourceAppId = "garmin.fit",
            sourceDeviceId = serial.takeIf { it != 0L }?.toString(),
            updatedEpochMs = System.currentTimeMillis(),
            source = SOURCE_GARMIN_FIT,
            sourceDetail = "$device · $fileName",
            groundContactMs = s.avgStanceTime?.takeIf { it > 0 },
            verticalOscillationMm = s.avgVerticalOscillation?.takeIf { it > 0 },
            verticalRatioPct = s.avgVerticalRatio?.takeIf { it > 0 },
            stepLengthMm = s.avgStepLength?.takeIf { it > 0 },
            groundContactBalancePct = s.avgStanceTimeBalance?.takeIf { it > 0 },
            aerobicTrainingEffect = s.totalTrainingEffect,
            anaerobicTrainingEffect = s.totalAnaerobicTrainingEffect,
        )
        return SyncedRun(run, samples, route)
    }

    /** FIT stores running cadence per leg (strides/min); report steps/min like Samsung does. */
    private fun runningCadence(perLeg: Short?, fractional: Float?): Float? {
        val v = perLeg?.toFloat() ?: return null
        if (v <= 0f) return null
        return (v + (fractional ?: 0f)) * 2
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /** FIT header: byte 0 = header size (12 or 14), bytes 8..11 = ".FIT". */
    private fun looksLikeFit(b: ByteArray) =
        b.size > 12 && (b[0].toInt() == 12 || b[0].toInt() == 14) &&
            String(b, 8, 4, Charsets.US_ASCII) == ".FIT"
}
