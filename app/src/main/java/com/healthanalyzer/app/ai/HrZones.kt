package com.healthanalyzer.app.ai

import com.healthanalyzer.app.data.RunAnalysis
import com.healthanalyzer.app.data.Sample
import kotlin.math.roundToInt

/** Time-in-zone summaries for a run's heart-rate rows. Pure JVM. */
object HrZones {

    /** Five zones: [starts] are the bpm where zones 2–5 begin. */
    class ZoneSet(val starts: List<Float>, val header: String, val names: List<String>)

    /**
     * Uses [customStarts] (bpm where zones 2–5 start) when given, otherwise 5 zones by % of
     * [maxHr]. Returns null without any zone definition.
     */
    fun zones(customStarts: List<Int>?, maxHr: Int?): ZoneSet? {
        if (customStarts != null) {
            val z = customStarts
            return ZoneSet(
                z.map { it.toFloat() },
                "Time in HR zones (athlete's zones, bpm)",
                listOf("Z1 <${z[0]}", "Z2 ${z[0]}–${z[1] - 1}", "Z3 ${z[1]}–${z[2] - 1}", "Z4 ${z[2]}–${z[3] - 1}", "Z5 ≥${z[3]}"),
            )
        }
        if (maxHr == null) return null
        return ZoneSet(
            listOf(0.6f, 0.7f, 0.8f, 0.9f).map { it * maxHr },
            "Time in HR zones (% of max HR $maxHr)",
            listOf("Z1 <60%", "Z2 60–70%", "Z3 70–80%", "Z4 80–90%", "Z5 ≥90%"),
        )
    }

    /** Seconds spent in each of the 5 zones; null without heart-rate data. */
    fun seconds(samples: List<Sample>, zones: ZoneSet): DoubleArray? {
        val hr = samples.filter { (it.hr ?: 0f) > 0 }.sortedBy { it.t }
        if (hr.size < 2) return null
        val secs = DoubleArray(5)
        for (i in 1 until hr.size) {
            val dt = (hr[i].t - hr[i - 1].t) / 1000.0
            if (dt <= 0 || dt > 10) continue // longer gaps are pauses
            val bpm = hr[i].hr!!
            secs[zones.starts.count { bpm >= it }] += dt
        }
        return secs.takeIf { it.sum() > 0 }
    }

    /** One line of time per zone, or null without heart-rate data or any zone definition. */
    fun timeInZones(samples: List<Sample>, customStarts: List<Int>?, maxHr: Int?): String? {
        val set = zones(customStarts, maxHr) ?: return null
        val secs = seconds(samples, set) ?: return null
        val total = secs.sum()
        return "${set.header}: " + set.names.indices.joinToString(" · ") { i ->
            "${set.names[i]} ${RunAnalysis.formatDuration(secs[i].roundToInt().toLong())} (${(secs[i] / total * 100).roundToInt()}%)"
        }
    }
}
