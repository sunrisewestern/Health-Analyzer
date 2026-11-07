package com.healthanalyzer.app.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.healthanalyzer.app.samsung.SyncedRun
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Access to the files of a Samsung Health "Download personal data" export, which is a folder
 * `samsunghealth_<user>_<timestamp>/` holding CSV tables plus `jsons/<table>/<x>/<uuid>.<column>.json`.
 * Only the exercise table and its JSON files are needed.
 */
interface SamsungExportFiles {
    /** Opens `com.samsung.shealth.exercise.<timestamp>.csv`, or null if the export has none. */
    fun openExerciseCsv(): InputStream?

    /** Opens a JSON file from `jsons/com.samsung.shealth.exercise/` by its file name. */
    fun openExerciseJson(fileName: String): InputStream?
}

/** A zipped export (e.g. the folder zipped and copied off the phone). Random access via [ZipFile]. */
class ZipSamsungExport(file: File) : SamsungExportFiles, AutoCloseable {
    private val zip = ZipFile(file)
    private val csv = zip.entries().asSequence()
        .filter { !it.isDirectory && SamsungExportImporter.isExerciseCsv(it.name.substringAfterLast('/')) }
        .maxByOrNull { it.name }
    private val jsons = zip.entries().asSequence()
        .filter { !it.isDirectory && "/${SamsungExportImporter.EXERCISE_TABLE}/" in "/${it.name}" && it.name.endsWith(".json") }
        .associateBy { it.name.substringAfterLast('/') }

    override fun openExerciseCsv(): InputStream? = csv?.let(zip::getInputStream)
    override fun openExerciseJson(fileName: String): InputStream? = jsons[fileName]?.let(zip::getInputStream)
    override fun close() = zip.close()
}

/** An unzipped export on a regular file system (used by tests / desktop runs). */
class DirectorySamsungExport(private val dir: File) : SamsungExportFiles {
    private val jsons: Map<String, File> by lazy {
        File(dir, "jsons/${SamsungExportImporter.EXERCISE_TABLE}").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".json") }
            .associateBy { it.name }
    }

    override fun openExerciseCsv(): InputStream? =
        dir.listFiles()?.filter { SamsungExportImporter.isExerciseCsv(it.name) }?.maxByOrNull { it.name }?.inputStream()

    override fun openExerciseJson(fileName: String): InputStream? = jsons[fileName]?.inputStream()
}

data class SamsungExportResult(
    val runsFound: Int,
    val exercisesRead: Int,
    val withDynamics: Int,
    val failures: List<String>,
)

/**
 * Reads running sessions from a Samsung Health data export. Unlike the Data SDK, the export also
 * carries Myotest running dynamics (contact / flight time, asymmetry, regularity, stiffness,
 * vertical oscillation) and sweat loss. Run ids match the SDK's (`<datauuid>#0`), so importing an
 * export enriches runs already synced instead of duplicating them. Pure JVM: no Android APIs.
 */
object SamsungExportImporter {

    const val EXERCISE_TABLE = "com.samsung.shealth.exercise"
    private const val COL = "com.samsung.health.exercise."
    private val CSV_NAME = Regex("""com\.samsung\.shealth\.exercise\.\d+\.csv""")

    /** Samsung Health export exercise_type codes → the SDK's type names used elsewhere in the app. */
    private val RUNNING_TYPES = mapOf(
        "1002" to "RUNNING",
        "15005" to "TREADMILL",
    )

    fun isExerciseCsv(name: String) = CSV_NAME.matches(name)

    /**
     * Parses every running session. Runs are handed to [onBatch] a few at a time so a large export
     * never sits in memory at once. [onProgress] gets (done, total).
     */
    suspend fun parse(
        files: SamsungExportFiles,
        batchSize: Int = 20,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onBatch: suspend (List<SyncedRun>) -> Unit,
    ): SamsungExportResult {
        val csv = files.openExerciseCsv()
            ?: throw IllegalArgumentException("No $EXERCISE_TABLE CSV found – pick the samsunghealth_… export folder or its zip")
        val rows = csv.use { readCsv(it.readBytes().toString(Charsets.UTF_8)) }
        val runRows = rows.filter { it[COL + "exercise_type"] in RUNNING_TYPES }
        val failures = mutableListOf<String>()
        var found = 0
        var dynamicsCount = 0
        val batch = mutableListOf<SyncedRun>()
        runRows.forEachIndexed { i, row ->
            onProgress(i, runRows.size)
            try {
                val run = toRun(row, files)
                if (run != null) {
                    found++
                    if (run.run.groundContactMs != null) dynamicsCount++
                    batch += run
                }
            } catch (e: Exception) {
                failures += "${row[COL + "start_time"]}: ${e.message ?: e.javaClass.simpleName}"
            }
            if (batch.size >= batchSize) { onBatch(batch.toList()); batch.clear() }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        onProgress(runRows.size, runRows.size)
        return SamsungExportResult(found, rows.size, dynamicsCount, failures)
    }

    private fun toRun(row: Map<String, String>, files: SamsungExportFiles): SyncedRun? {
        val uuid = row[COL + "datauuid"]?.takeIf { it.isNotBlank() } ?: return null
        val start = parseUtc(row[COL + "start_time"]) ?: return null
        val end = parseUtc(row[COL + "end_time"]) ?: start
        val offset = parseOffset(row[COL + "time_offset"])

        val samples = json(files, row[COL + "live_data"])?.asArrayOrNull()?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o.long("start_time") ?: return@mapNotNull null
            Sample(t = t, hr = o.float("heart_rate"), cadence = o.float("cadence"), speed = o.float("speed"), power = o.float("power"))
        }.orEmpty().sortedBy { it.t }

        val route = json(files, row[COL + "location_data"])?.asArrayOrNull()?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            RoutePoint(
                t = o.long("start_time") ?: return@mapNotNull null,
                lat = o.float("latitude") ?: return@mapNotNull null,
                lon = o.float("longitude") ?: return@mapNotNull null,
                alt = o.float("altitude"),
                acc = o.float("accuracy"),
            )
        }.orEmpty().sortedBy { it.t }

        val dyn = dynamics(json(files, row["additional_internal"]))

        val run = RunEntity(
            id = "$uuid#0",
            dataPointUid = uuid,
            exerciseType = RUNNING_TYPES.getValue(row[COL + "exercise_type"]!!),
            startEpochMs = start,
            endEpochMs = end,
            zoneOffsetSec = offset,
            title = row["title"]?.takeIf { it.isNotBlank() },
            durationSec = (row.num(COL + "duration")?.div(1000.0))?.roundToLong() ?: ((end - start) / 1000),
            distanceM = row.f(COL + "distance"),
            calories = row.f(COL + "calorie") ?: row.f("total_calorie") ?: 0f,
            meanHeartRate = row.f(COL + "mean_heart_rate")?.takeIf { it > 0 },
            maxHeartRate = row.f(COL + "max_heart_rate")?.takeIf { it > 0 },
            minHeartRate = row.f(COL + "min_heart_rate")?.takeIf { it > 0 },
            meanSpeed = row.f(COL + "mean_speed"),
            maxSpeed = row.f(COL + "max_speed"),
            meanCadence = row.f(COL + "mean_cadence")?.takeIf { it > 0 },
            maxCadence = row.f(COL + "max_cadence")?.takeIf { it > 0 },
            altitudeGain = row.f(COL + "altitude_gain"),
            altitudeLoss = row.f(COL + "altitude_loss"),
            maxAltitude = row.f(COL + "max_altitude"),
            minAltitude = row.f(COL + "min_altitude"),
            meanPower = row.f(COL + "mean_power")?.takeIf { it > 0 },
            maxPower = row.f(COL + "max_power")?.takeIf { it > 0 },
            vo2Max = row.f(COL + "vo2_max")?.takeIf { it > 0 },
            stepCount = row.num(COL + "count")?.toInt(),
            sampleCount = samples.size,
            routePointCount = route.size,
            sourceAppId = row[COL + "pkg_name"]?.takeIf { it.isNotBlank() },
            sourceDeviceId = row[COL + "deviceuuid"]?.takeIf { it.isNotBlank() },
            updatedEpochMs = parseUtc(row[COL + "update_time"]),
            source = SOURCE_SAMSUNG,
            sourceDetail = "Samsung Health export",
            groundContactMs = dyn["effective_contact_time"],
            verticalOscillationMm = dyn["undulation"]?.times(1000f), // metres → mm
            flightTimeMs = dyn["effective_flight_time"],
            asymmetryPct = dyn["asymmetry"]?.times(100f),
            regularityPct = dyn["regularity"]?.times(100f),
            stiffnessKnM = dyn["stiffness"]?.div(1000f), // N/m → kN/m
            sweatLossMl = row.f(COL + "sweat_loss")?.takeIf { it > 0 },
        )
        return SyncedRun(run, samples, route)
    }

    /**
     * Averages the Myotest chart data in additional_internal.json. Zero values mark gaps (no
     * measurement in that 10 s window) and are skipped; asymmetry is averaged as a magnitude.
     */
    internal fun dynamics(root: JsonElement?): Map<String, Float> {
        val data = (root as? JsonObject)?.get("data")?.asArrayOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, Float>()
        data.forEach { service ->
            val metrics = (service as? JsonObject)?.get("advanced_metrics")?.asArrayOrNull() ?: return@forEach
            metrics.forEach { m ->
                val mo = m as? JsonObject ?: return@forEach
                val type = mo.get("data_type")?.asString ?: return@forEach
                val values = mo.get("chart_data")?.asArrayOrNull()
                    ?.mapNotNull { (it as? JsonObject)?.float("value") }
                    ?.filter { it != 0f && it.isFinite() }
                    ?.map { if (type == "asymmetry") abs(it) else it }
                    .orEmpty()
                if (values.isNotEmpty()) out[type] = values.average().toFloat()
            }
        }
        return out
    }

    private fun json(files: SamsungExportFiles, fileName: String?): JsonElement? {
        if (fileName.isNullOrBlank()) return null
        val stream = files.openExerciseJson(fileName) ?: return null
        return stream.use { JsonParser.parseReader(InputStreamReader(it, Charsets.UTF_8)) }
    }

    // ---- small parsing helpers ---------------------------------------------------------------

    private val TIME = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .toFormatter()

    /** Export timestamps are UTC wall-clock strings, e.g. "2026-11-01 21:19:02.017". */
    internal fun parseUtc(s: String?): Long? = s?.takeIf { it.isNotBlank() }?.let {
        runCatching { LocalDateTime.parse(it.trim(), TIME).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** "UTC-0500" → -18000. */
    internal fun parseOffset(s: String?): Int? {
        val m = Regex("""UTC([+-])(\d{2})(\d{2})""").matchEntire(s?.trim() ?: return null) ?: return null
        val sec = m.groupValues[2].toInt() * 3600 + m.groupValues[3].toInt() * 60
        return if (m.groupValues[1] == "-") -sec else sec
    }

    private fun Map<String, String>.num(key: String) = this[key]?.trim()?.toDoubleOrNull()
    private fun Map<String, String>.f(key: String) = num(key)?.toFloat()
    private fun JsonObject.float(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asFloat
    private fun JsonObject.long(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asLong
    private fun JsonElement.asArrayOrNull(): JsonArray? = if (isJsonArray) asJsonArray else null

    /**
     * The export's CSVs start with a metadata line (`table,version,columns`), then the header.
     * Fields may be quoted and contain commas, quotes ("") or newlines.
     */
    internal fun readCsv(text: String): List<Map<String, String>> {
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var quoted = false
        var i = if (text.startsWith('﻿')) 1 else 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { record += field.toString(); field = StringBuilder() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    record += field.toString(); field = StringBuilder()
                    records += record; record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) { record += field.toString(); records += record }

        val headerIndex = if (records.firstOrNull()?.firstOrNull()?.startsWith("com.samsung.") == true) 1 else 0
        val header = records.getOrNull(headerIndex) ?: return emptyList()
        return records.drop(headerIndex + 1).filter { it.any(String::isNotEmpty) }.map { values ->
            header.withIndex().associate { (idx, name) -> name to values.getOrElse(idx) { "" } }
        }
    }
}
