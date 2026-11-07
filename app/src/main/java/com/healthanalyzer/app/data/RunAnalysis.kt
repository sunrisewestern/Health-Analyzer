package com.healthanalyzer.app.data

import kotlin.math.roundToInt

/** One kilometre (or the final partial km) of a run. */
data class Split(
    val index: Int,
    val distanceM: Double,
    val durationSec: Double,
    val meanHeartRate: Float?,
    val meanCadence: Float?,
) {
    val paceSecPerKm: Double get() = if (distanceM > 0) durationSec / distanceM * 1000 else 0.0
}

object RunAnalysis {

    /**
     * Builds km splits by integrating speed over time.
     *
     * Samsung's exercise log interleaves two kinds of rows: heart-rate rows (~1 Hz) and
     * speed/cadence rows (~1 Hz, offset by a few hundred ms). So distance is integrated
     * only between consecutive speed rows; heart-rate rows just feed the split averages.
     *
     * If [totalDistanceM] is given (Samsung's own session distance), the integrated
     * distance is rescaled to match it, so the splits add up to the reported total.
     */
    fun splits(samples: List<Sample>, totalDistanceM: Float? = null, splitM: Double = 1000.0): List<Split> {
        val sorted = samples.sortedBy { it.t }
        val scale = totalDistanceM
            ?.takeIf { it > 0 }
            ?.let { total -> integratedDistance(sorted).takeIf { it > 0 }?.let { total / it } }
            ?: 1.0

        val result = mutableListOf<Split>()
        var dist = 0.0
        var time = 0.0
        val hrs = mutableListOf<Float>()
        val cads = mutableListOf<Float>()

        fun close() {
            result += Split(
                index = result.size + 1,
                distanceM = dist,
                durationSec = time,
                meanHeartRate = hrs.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                meanCadence = cads.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            )
            dist = 0.0; time = 0.0; hrs.clear(); cads.clear()
        }

        var lastSpeedT: Long? = null
        for (s in sorted) {
            s.hr?.takeIf { it > 0 }?.let(hrs::add)
            val speed = s.speed ?: continue
            s.cadence?.takeIf { it > 0 }?.let(cads::add)
            lastSpeedT?.let { prevT ->
                val dt = (s.t - prevT) / 1000.0
                if (dt > 0 && dt <= MAX_GAP_SEC) { // larger gaps are pauses
                    dist += speed * dt * scale
                    time += dt
                }
            }
            lastSpeedT = s.t
            if (dist >= splitM) close()
        }
        if (dist >= MIN_LAST_SPLIT_M) close()
        return result
    }

    private fun integratedDistance(sorted: List<Sample>): Double {
        var d = 0.0
        var lastT: Long? = null
        for (s in sorted) {
            val speed = s.speed ?: continue
            lastT?.let { prevT ->
                val dt = (s.t - prevT) / 1000.0
                if (dt > 0 && dt <= MAX_GAP_SEC) d += speed * dt
            }
            lastT = s.t
        }
        return d
    }

    fun formatPace(secPerKm: Double): String {
        if (secPerKm <= 0 || secPerKm.isInfinite() || secPerKm.isNaN()) return "–"
        val s = secPerKm.roundToInt()
        return "%d'%02d\"".format(s / 60, s % 60)
    }

    fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private const val MAX_GAP_SEC = 30.0
    private const val MIN_LAST_SPLIT_M = 50.0
}
