package com.healthanalyzer.app.debug

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Debug-only probe: requests READ on every Samsung Health Data SDK type, then for each type
 * records how many points exist in the last [DAYS] days, their date range, sources and a
 * reflected dump of a few sample points. Output: files/sdk-probe.json.
 *
 * adb shell am start -n com.healthanalyzer.app.dev/com.healthanalyzer.app.debug.SamsungProbeActivity
 */
class SamsungProbeActivity : ComponentActivity() {

    private val out = JSONObject()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Probing Samsung Health…"; setPadding(48, 96, 48, 48) }
        setContentView(status)
        lifecycleScope.launch { runCatching { probe() }.onFailure { log("FATAL $it"); out.put("fatal", it.toString()) }; finishUp() }
    }

    private suspend fun probe() {
        val store = HealthDataService.getStore(applicationContext)
        val types: List<Pair<String, DataType>> = DataTypes::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && DataType::class.java.isAssignableFrom(it.type) }
            .map { it.name to it.get(null) as DataType }

        val wanted = types.mapNotNull { (_, t) -> runCatching { Permission.of(t, AccessType.READ) }.getOrNull() }.toSet()
        val granted = runCatching { store.requestPermissions(wanted, this) }
            .getOrElse { log("requestPermissions failed: $it"); store.getGrantedPermissions(wanted) }
        out.put("requested", wanted.size).put("granted", granted.size)

        out.put("devices", JSONArray().apply {
            runCatching { store.getDeviceManager().getOwnDevices() }.getOrElse { log("devices: $it"); emptyList<com.samsung.android.sdk.health.data.device.Device>() }
                .forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("model", it.model)
                    .put("manufacturer", it.manufacturer).put("type", it.deviceType.toString())) }
        })

        val now = Instant.now()
        val since = now.minus(Duration.ofDays(DAYS))
        val result = JSONObject()
        for ((name, type) in types) {
            status.text = "Reading $name…"
            val entry = JSONObject()
            entry.put("granted", granted.any { it.toString().contains(name, ignoreCase = true) } ||
                runCatching { Permission.of(type, AccessType.READ) in granted }.getOrDefault(false))
            entry.put("fields", JSONArray(fieldsOf(type).map { it.first }))
            if (type !is DataType.Readable<*, *>) { entry.put("readable", false); result.put(name, entry); continue }
            runCatching { readAll(store, type, since, now, entry) }
                .onFailure { entry.put("error", it.toString()); log("$name: $it") }
            result.put(name, entry)
            log("$name -> count=${entry.optInt("count", -1)} ${entry.optString("error")}")
        }
        out.put("types", result)
        status.text = "Aggregates…"
        out.put("aggregates", aggregates(store))
        out.put("exercise", exerciseCoverage)
        out.put("sourceDevices", JSONObject().apply {
            allDeviceIds.forEach { id ->
                val d = runCatching { store.getDeviceManager().getDevice(id) }.getOrNull()
                put(id, if (d == null) JSONObject.NULL else JSONObject().put("name", d.name).put("model", d.model)
                    .put("manufacturer", d.manufacturer).put("type", d.deviceType.toString()))
            }
        })
    }

    private val allDeviceIds = mutableSetOf<String>()
    private val exerciseCoverage = JSONObject()

    /** Per exercise type: session count and which session / per-sample log properties are ever non-null. */
    private fun noteExercise(p: HealthDataPoint) {
        val sessions = runCatching { p.getValue(DataType.ExerciseType.SESSIONS) }.getOrNull().orEmpty()
        sessions.forEach { s ->
            val key = s.exerciseType.toString()
            val e = exerciseCoverage.optJSONObject(key) ?: JSONObject().also { exerciseCoverage.put(key, it) }
            e.put("count", e.optInt("count") + 1)
            val sf = e.optJSONObject("sessionFields") ?: JSONObject().also { e.put("sessionFields", it) }
            nonNullGetters(s).forEach { (k, v) -> sf.put(k, sf.optInt(k) + 1); if (v is Collection<*>) e.put("${k}_maxSize", maxOf(e.optInt("${k}_maxSize"), v.size)) }
            val lf = e.optJSONObject("logFields") ?: JSONObject().also { e.put("logFields", it) }
            s.log.orEmpty().take(200).flatMap { nonNullGetters(it).map { it.first } }.toSet().forEach { lf.put(it, lf.optInt(it) + 1) }
            s.route.orEmpty().firstOrNull()?.let { r -> e.put("routeFields", JSONArray(nonNullGetters(r).map { it.first })) }
        }
    }

    private fun nonNullGetters(v: Any): List<Pair<String, Any>> = v.javaClass.methods.filter {
        it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) && (it.name.startsWith("get") || it.name.startsWith("is")) && it.name != "getClass"
    }.mapNotNull { m -> runCatching { m.invoke(v) }.getOrNull()?.let { m.name.removePrefix("get") to it } }

    private suspend fun aggregates(store: com.samsung.android.sdk.health.data.HealthDataStore): JSONObject {
        val o = JSONObject()
        val today = java.time.LocalDate.now()
        val ops = mapOf(
            "STEPS.TOTAL" to DataType.StepsType.TOTAL,
            "ACTIVITY.TOTAL_ACTIVE_CALORIES_BURNED" to DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED,
            "ACTIVITY.TOTAL_CALORIES_BURNED" to DataType.ActivitySummaryType.TOTAL_CALORIES_BURNED,
            "ACTIVITY.TOTAL_ACTIVE_TIME" to DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME,
            "ACTIVITY.TOTAL_DISTANCE" to DataType.ActivitySummaryType.TOTAL_DISTANCE,
        )
        for ((name, op) in ops) runCatching {
            val b = op.requestBuilder as com.samsung.android.sdk.health.data.request.AggregateRequest.LocalTimeBuilder<*>
            b.setLocalTimeFilterWithGroup(
                LocalTimeFilter.of(today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay()),
                com.samsung.android.sdk.health.data.request.LocalTimeGroup.of(com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit.DAILY, 1),
            )
            val r = store.aggregateData(b.build())
            o.put(name, JSONArray(r.dataList.map { val a = it as com.samsung.android.sdk.health.data.data.AggregatedData<*>; "${a.startTime}=${a.value}" }))
        }.onFailure { o.put(name, "ERR $it") }
        val goals = DataTypes::class.java.fields.filter { it.name.endsWith("_GOAL") }.map { it.name to it.get(null) }
        for ((gName, g) in goals) g.javaClass.fields.filter { Modifier.isStatic(it.modifiers) && it.type.simpleName == "AggregateOperation" }.forEach { f ->
            val name = "$gName.${f.name}"
            runCatching {
                val op = f.get(null) as com.samsung.android.sdk.health.data.data.AggregateOperation<*, *>
                val b = op.requestBuilder as com.samsung.android.sdk.health.data.request.AggregateRequest.AllSourceLocalDateBuilder<*>
                b.setLocalDateFilter(com.samsung.android.sdk.health.data.request.LocalDateFilter.of(today.minusDays(6), today.plusDays(1)))
                val r = store.aggregateData(b.build())
                o.put(name, JSONArray(r.dataList.map { val a = it as com.samsung.android.sdk.health.data.data.AggregatedData<*>; "${a.startTime}=${a.value}" }))
            }.onFailure { o.put(name, "ERR $it") }
        }
        return o
    }

    private suspend fun readAll(
        store: com.samsung.android.sdk.health.data.HealthDataStore,
        type: DataType, since: Instant, until: Instant, entry: JSONObject,
    ) {
        val fields = fieldsOf(type)
        var count = 0; var pages = 0
        var first: Instant? = null; var last: Instant? = null
        val sources = mutableMapOf<String, Int>()
        val samples = JSONArray()
        var token: String? = null
        do {
            val builder = (type as DataType.Readable<*, *>).readDataRequestBuilder as Any
            applyTimeFilter(builder, since, until)
            call(builder, "setPageSize", 500)
            token?.let { call(builder, "setPageToken", it) }
            @Suppress("UNCHECKED_CAST")
            val req = builder.javaClass.getMethod("build").invoke(builder) as ReadDataRequest<com.samsung.android.sdk.health.data.data.DataPoint>
            val resp = store.readData(req)
            resp.dataList.filterIsInstance<HealthDataPoint>().forEach { p ->
                count++
                p.dataSource?.deviceId?.let { allDeviceIds += it }
                if (type == DataTypes.EXERCISE) noteExercise(p)
                val st = p.startTime
                if (st != null) { if (first == null || st < first) first = st; if (last == null || st > last) last = st }
                val src = "${p.dataSource?.appId}|${p.dataSource?.deviceId}"
                sources[src] = (sources[src] ?: 0) + 1
                if (samples.length() < 3) samples.put(dumpPoint(p, fields))
            }
            token = resp.pageToken; pages++
        } while (!token.isNullOrEmpty() && pages < 40)
        entry.put("count", count).put("truncated", pages >= 40)
            .put("first", first?.toString()).put("last", last?.toString())
            .put("sources", JSONObject(sources as Map<*, *>)).put("samples", samples)
    }

    private fun applyTimeFilter(builder: Any, since: Instant, until: Instant) {
        val m = builder.javaClass.methods
        m.firstOrNull { it.name == "setInstantTimeFilter" }?.let { it.invoke(builder, InstantTimeFilter.of(since, until)); return }
        m.firstOrNull { it.name == "setLocalTimeFilter" }?.let {
            it.invoke(builder, LocalTimeFilter.of(LocalDateTime.now().minusDays(DAYS), LocalDateTime.now())); return
        }
    }

    private fun call(target: Any, name: String, arg: Any) {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 1 }?.invoke(target, arg)
    }

    private fun fieldsOf(type: DataType): List<Pair<String, Field<*>>> =
        type.javaClass.fields.filter { Modifier.isStatic(it.modifiers) && it.type == Field::class.java }
            .map { it.name to it.get(null) as Field<*> }

    private fun dumpPoint(p: HealthDataPoint, fields: List<Pair<String, Field<*>>>): JSONObject {
        val o = JSONObject().put("_start", p.startTime?.toString()).put("_end", p.endTime?.toString())
            .put("_source", "${p.dataSource?.appId}|${p.dataSource?.deviceId}")
        fields.forEach { (n, f) ->
            @Suppress("UNCHECKED_CAST")
            val v = runCatching { p.getValue(f as Field<Any?>) }.getOrElse { "ERR ${it.javaClass.simpleName}" }
            if (v != null) o.put(n, dump(v, 0))
        }
        return o
    }

    /** Reflective, depth/size-limited JSON dump of SDK value objects. */
    private fun dump(v: Any?, depth: Int): Any? = when {
        v == null -> JSONObject.NULL
        v is Number || v is Boolean || v is String -> v
        v is Enum<*> || v is Instant || v is Duration || v is java.time.temporal.Temporal || v is java.time.ZoneOffset -> v.toString()
        depth > 4 -> v.toString().take(200)
        v is Collection<*> -> JSONObject().put("_size", v.size)
            .put("items", JSONArray(v.take(if (depth == 0) 2 else 3).map { dump(it, depth + 1) }))
        v is Map<*, *> -> JSONObject().apply { v.entries.take(10).forEach { put(it.key.toString(), dump(it.value, depth + 1)) } }
        else -> JSONObject().apply {
            put("_class", v.javaClass.simpleName)
            v.javaClass.methods.filter {
                it.parameterCount == 0 && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                    (it.name.startsWith("get") || it.name.startsWith("is")) && it.name !in setOf("getClass")
            }.forEach { m ->
                runCatching { m.invoke(v) }.onSuccess { r -> if (r != null) put(m.name.removePrefix("get"), dump(r, depth + 1)) }
            }
        }
    }

    private fun finishUp() {
        val f = File(filesDir, "sdk-probe.json")
        f.writeText(out.toString(2))
        log("DONE wrote ${f.absolutePath} (${f.length()} bytes)")
        status.text = "Done. Wrote ${f.absolutePath}"
    }

    private fun log(s: String) = Log.i("SdkProbe", s)

    companion object { const val DAYS = 120L }
}
