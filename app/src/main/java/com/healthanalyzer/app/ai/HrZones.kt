package com.healthanalyzer.app.ai

import com.healthanalyzer.app.data.RunAnalysis
import com.healthanalyzer.app.data.Sample
import kotlin.math.roundToInt

/** Time-in-zone summaries for a run's heart-rate rows. Pure JVM. */
object HrZones {

    /**
     * Uses [customStarts] (bpm where zones 2–5 start) when given, otherwise 5 zones by % of
     * [maxHr]. Returns null without heart-rate data or without any zone definition.
     */
    fun timeInZones(samples: List<Sample>, customStarts: List<Int>?, maxHr: Int?): String? {
        val starts: List<Float>
        val header: String
        val names: List<String>
        if (customStarts != null) {
            val z = customStarts
            starts = z.map { it.toFloat() }
            header = "Time in HR zones (athlete's zones, bpm)"
            names = listOf("Z1 <${z[0]}", "Z2 ${z[0]}–${z[1] - 1}", "Z3 ${z[1]}–${z[2] - 1}", "Z4 ${z[2]}–${z[3] - 1}", "Z5 ≥${z[3]}")
        } else {
            if (maxHr == null) return null
            starts = listOf(0.6f, 0.7f, 0.8f, 0.9f).map { it * maxHr }
            header = "Time in HR zones (% of max HR $maxHr)"
            names = listOf("Z1 <60%", "Z2 60–70%", "Z3 70–80%", "Z4 80–90%", "Z5 ≥90%")
        }
        val hr = samples.filter { (it.hr ?: 0f) > 0 }.sortedBy { it.t }
        if (hr.size < 2) return null
        val secs = DoubleArray(5)
        for (i in 1 until hr.size) {
            val dt = (hr[i].t - hr[i - 1].t) / 1000.0
            if (dt <= 0 || dt > 30) continue
            val bpm = hr[i].hr!!
            secs[starts.count { bpm >= it }] += dt
        }
        val total = secs.sum().takeIf { it > 0 } ?: return null
        return "$header: " + names.indices.joinToString(" · ") { i ->
            "${names[i]} ${RunAnalysis.formatDuration(secs[i].roundToInt().toLong())} (${(secs[i] / total * 100).roundToInt()}%)"
        }
    }
}
