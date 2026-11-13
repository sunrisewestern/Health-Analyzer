package com.healthanalyzer.app.data

import kotlin.math.roundToInt

/** One kilometre (or the final partial km) of a run. Time and pace count moving time only. */
data class Split(
    val index: Int,
    val distanceM: Double,
    val durationSec: Double,
    val meanHeartRate: Float?,
    val meanCadence: Float?,
    val maxHeartRate: Float? = null,
    val meanPower: Float? = null,
    /** Climb and descent within the split, m, from the smoothed altitude track; null without altitude. */
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    /** Time spent paused inside this split, s. */
    val pausedSec: Double = 0.0,
) {
    val paceSecPerKm: Double get() = if (distanceM > 0) durationSec / distanceM * 1000 else 0.0

    /** Average step length, m, from pace and cadence. */
    val stepLengthM: Double? get() = meanCadence?.takeIf { it > 0 && durationSec > 0 }
        ?.let { distanceM / (it / 60.0 * durationSec) }
}

/** A stop in the recording (auto-pause or manual), with the heart rate just before and at the restart. */
data class Pause(
    val atDistanceM: Double,
    val durationSec: Double,
    val hrBefore: Float?,
    val hrAfter: Float?,
)

/** Pace and heart rate over one half of the moving time. */
data class HalfSummary(val paceSecPerKm: Double, val meanHeartRate: Double)

class RunBreakdown(val splits: List<Split>, val pauses: List<Pause>)

object RunAnalysis {

    fun splits(samples: List<Sample>, totalDistanceM: Float? = null, splitM: Double = 1000.0): List<Split> =
        breakdown(samples, emptyList(), totalDistanceM, splitM).splits

    /**
     * Builds km splits and the list of pauses by integrating speed over time.
     *
     * Samsung's exercise log interleaves two kinds of rows: heart-rate rows (~1 Hz) and
     * speed/cadence rows (~1 Hz, offset by a few hundred ms). So distance is integrated
     * only between consecutive speed rows; heart-rate rows just feed the split averages.
     * Nothing is logged while the watch is paused, so a gap between speed rows is a pause.
     *
     * If [totalDistanceM] is given (Samsung's own session distance), the integrated
     * distance is rescaled to match it, so the splits add up to the reported total.
     * [route] adds climb and descent per split when it carries altitude.
     */
    fun breakdown(
        samples: List<Sample>,
        route: List<RoutePoint> = emptyList(),
        totalDistanceM: Float? = null,
        splitM: Double = 1000.0,
    ): RunBreakdown {
        val sorted = samples.sortedBy { it.t }
        val scale = totalDistanceM
            ?.takeIf { it > 0 }
            ?.let { total -> integratedDistance(sorted).takeIf { it > 0 }?.let { total / it } }
            ?: 1.0

        val result = mutableListOf<Split>()
        val endTimes = mutableListOf<Long>()
        val pauses = mutableListOf<Pause>()
        var total = 0.0
        var dist = 0.0
        var time = 0.0
        var paused = 0.0
        val hrs = mutableListOf<Float>()
        val cads = mutableListOf<Float>()
        val powers = mutableListOf<Float>()

        fun close(t: Long) {
            result += Split(
                index = result.size + 1,
                distanceM = dist,
                durationSec = time,
                meanHeartRate = hrs.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                meanCadence = cads.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                maxHeartRate = hrs.maxOrNull(),
                meanPower = powers.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                pausedSec = paused,
            )
            endTimes += t
            dist = 0.0; time = 0.0; paused = 0.0; hrs.clear(); cads.clear(); powers.clear()
        }

        var lastSpeedT: Long? = null
        // Heart-rate and speed rows restart in either order after a pause, so the heart rate
        // "before" is the one seen by the last speed row, and "after" is the first one later than it.
        var lastHr: Float? = null
        var lastHrT = 0L
        var hrAtLastSpeed: Float? = null
        var pauseAwaitingHr = false
        for (s in sorted) {
            s.hr?.takeIf { it > 0 }?.let { hr ->
                hrs += hr
                lastHr = hr
                lastHrT = s.t
                if (pauseAwaitingHr) {
                    pauses[pauses.lastIndex] = pauses.last().copy(hrAfter = hr)
                    pauseAwaitingHr = false
                }
            }
            val speed = s.speed ?: continue
            s.cadence?.takeIf { it > 0 }?.let(cads::add)
            s.power?.takeIf { it > 0 }?.let(powers::add)
            lastSpeedT?.let { prevT ->
                val dt = (s.t - prevT) / 1000.0
                if (dt > MAX_GAP_SEC) {
                    val restarted = lastHr.takeIf { lastHrT > prevT + 2000 }
                    pauses += Pause(total, dt, hrAtLastSpeed, restarted)
                    pauseAwaitingHr = restarted == null
                    paused += dt
                } else if (dt > 0) {
                    dist += speed * dt * scale
                    total += speed * dt * scale
                    time += dt
                }
            }
            lastSpeedT = s.t
            hrAtLastSpeed = lastHr
            if (dist >= splitM) close(s.t)
        }
        if (dist >= MIN_LAST_SPLIT_M) close(lastSpeedT ?: 0) else if (result.isNotEmpty() && paused > 0) {
            result[result.lastIndex] = result.last().let { it.copy(pausedSec = it.pausedSec + paused) }
        }
        return RunBreakdown(withElevation(result, endTimes, route), pauses)
    }

    /**
     * Adds climb and descent to each split. Altitude is smoothed over a few points and only
     * changes of [ALT_STEP_M] or more count, so sensor jitter doesn't add up to fake climbing.
     * (This is stricter than the watch, so the splits sum to less than its total gain.)
     */
    private fun withElevation(splits: List<Split>, endTimes: List<Long>, route: List<RoutePoint>): List<Split> {
        val pts = route.filter { it.alt != null }.sortedBy { it.t }
        if (splits.isEmpty() || pts.size < 2 * ALT_SMOOTH + 1) return splits
        val gain = DoubleArray(splits.size)
        val loss = DoubleArray(splits.size)
        var ref: Double? = null
        var i = 0
        for (k in pts.indices) {
            val from = maxOf(0, k - ALT_SMOOTH)
            val to = minOf(pts.lastIndex, k + ALT_SMOOTH)
            var sum = 0.0
            for (j in from..to) sum += pts[j].alt!!
            val alt = sum / (to - from + 1)
            while (i < splits.lastIndex && pts[k].t > endTimes[i]) i++
            val r = ref
            if (r == null) ref = alt
            else if (alt - r >= ALT_STEP_M) { gain[i] += alt - r; ref = alt }
            else if (r - alt >= ALT_STEP_M) { loss[i] += r - alt; ref = alt }
        }
        return splits.mapIndexed { n, s -> s.copy(elevationGainM = gain[n], elevationLossM = loss[n]) }
    }

    /** Pace and heart rate for the first and second half of the moving time (pauses left out). */
    fun halves(samples: List<Sample>, totalDistanceM: Float? = null): Pair<HalfSummary, HalfSummary>? {
        val sorted = samples.sortedBy { it.t }
        val speedRows = sorted.filter { it.speed != null }
        if (speedRows.size < 20) return null
        val raw = integratedDistance(sorted)
        val scale = totalDistanceM?.takeIf { it > 0 && raw > 0 }?.let { it / raw } ?: 1.0
        fun moving(a: Sample, b: Sample) = ((b.t - a.t) / 1000.0).takeIf { it > 0 && it <= MAX_GAP_SEC }
        val movingSec = speedRows.zipWithNext().sumOf { (a, b) -> moving(a, b) ?: 0.0 }
        val time = DoubleArray(2)
        val dist = DoubleArray(2)
        var elapsed = 0.0
        var midT = speedRows.last().t
        speedRows.zipWithNext().forEach { (a, b) ->
            val dt = moving(a, b) ?: return@forEach
            val half = if (elapsed < movingSec / 2) 0 else 1
            if (half == 0) midT = b.t
            time[half] += dt
            dist[half] += b.speed!! * dt * scale
            elapsed += dt
        }
        val hr = sorted.filter { (it.hr ?: 0f) > 0 }.partition { it.t <= midT }
        if (dist.any { it <= 0 } || hr.first.isEmpty() || hr.second.isEmpty()) return null
        return HalfSummary(time[0] / dist[0] * 1000, hr.first.map { it.hr!!.toDouble() }.average()) to
            HalfSummary(time[1] / dist[1] * 1000, hr.second.map { it.hr!!.toDouble() }.average())
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

    /** Rows arrive about once a second while recording; a longer gap is a pause. */
    private const val MAX_GAP_SEC = 10.0
    private const val ALT_SMOOTH = 2
    private const val ALT_STEP_M = 0.5
    private const val MIN_LAST_SPLIT_M = 50.0
}
