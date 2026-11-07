package com.healthanalyzer.app.ai

import com.healthanalyzer.app.data.RunAnalysis
import com.healthanalyzer.app.data.RunEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Long-term running statistics as prompt text ({{running_stats:N}}). Pure Kotlin, no Android. */
object RunningStats {

    fun build(runs: List<RunEntity>, months: Int, maxHr: Int?, today: LocalDate = LocalDate.now()): String {
        if (runs.isEmpty()) return "(no runs in the last $months months)"
        val sorted = runs.sortedBy { it.startEpochMs }
        val first = PromptRenderer.localStart(sorted.first()).toLocalDate()
        val weeks = (ChronoUnit.DAYS.between(first, today).coerceAtLeast(7) / 7.0)
        val totalKm = sorted.sumOf { km(it) }
        val last4 = sorted.filter { !PromptRenderer.localStart(it).toLocalDate().isBefore(today.minusDays(28)) }

        return buildString {
            appendLine("Running statistics, last $months months (${first} to $today):")
            appendLine(
                "Runs: ${sorted.size} (outdoor ${sorted.count { it.exerciseType == "RUNNING" }}, " +
                    "treadmill ${sorted.count { it.exerciseType == "TREADMILL" }}, " +
                    "track ${sorted.count { it.exerciseType == "TRACK_RUNNING" }})",
            )
            appendLine(
                "Total: %.1f km in %s · Average %.1f km/week · Last 4 weeks: %.1f km/week over %d runs".format(
                    totalKm, RunAnalysis.formatDuration(sorted.sumOf { it.durationSec }), totalKm / weeks,
                    last4.sumOf { km(it) } / 4, last4.size,
                ),
            )

            appendLine()
            appendLine("Monthly: month | runs | km | avg pace/km | avg HR | longest km | VO2max (last in month)")
            sorted.groupBy { YearMonth.from(PromptRenderer.localStart(it)) }.toSortedMap().forEach { (month, list) ->
                val dist = list.sumOf { km(it) }
                val time = list.sumOf { it.durationSec }
                appendLine(
                    listOf(
                        month.toString(),
                        list.size.toString(),
                        "%.1f".format(dist),
                        if (dist > 0) RunAnalysis.formatPace(time / dist) else "–",
                        weightedHr(list)?.let { "%.0f".format(it) } ?: "–",
                        "%.1f".format(list.maxOf { km(it) }),
                        list.lastOrNull { it.vo2Max != null }?.vo2Max?.let { "%.1f".format(it) } ?: "–",
                    ).joinToString(" | "),
                )
            }

            appendLine()
            appendLine("Bests (outdoor/track runs; treadmill pace excluded as less reliable):")
            val longest = sorted.maxBy { km(it) }
            appendLine("Longest run: ${line(longest)}")
            val ground = sorted.filter { it.exerciseType != "TREADMILL" }
            for (minKm in listOf(5.0, 10.0, 15.0, 21.0)) {
                val best = ground.filter { km(it) >= minKm }.minByOrNull { pace(it) } ?: continue
                appendLine("Fastest average pace over ≥${minKm.toInt()} km: ${line(best)}")
            }
            val vo2 = sorted.filter { it.vo2Max != null }
            if (vo2.isNotEmpty()) {
                val firstV = vo2.first()
                val lastV = vo2.last()
                appendLine(
                    "VO2max estimate: %.1f on %s → %.1f on %s (highest %.1f)".format(
                        firstV.vo2Max, date(firstV), lastV.vo2Max, date(lastV), vo2.maxOf { it.vo2Max!! },
                    ),
                )
            }

            if (maxHr != null) {
                val withHr = sorted.filter { (it.meanHeartRate ?: 0f) > 0f && km(it) >= 3 }
                val easy = withHr.filter { it.meanHeartRate!! < maxHr * 0.75f }
                val hard = withHr.filter { it.meanHeartRate!! >= maxHr * 0.85f }
                appendLine()
                appendLine(
                    "Intensity by average HR (max HR $maxHr): easy <75%%: %d runs · moderate: %d · hard ≥85%%: %d".format(
                        easy.size, withHr.size - easy.size - hard.size, hard.size,
                    ),
                )
                if (easy.isNotEmpty()) {
                    appendLine(
                        "Typical easy run: median pace %s/km at median %.0f bpm, cadence %s spm (from %d runs)".format(
                            RunAnalysis.formatPace(median(easy.map { pace(it) })),
                            median(easy.map { it.meanHeartRate!!.toDouble() }),
                            easy.mapNotNull { it.meanCadence?.toDouble() }.takeIf { it.isNotEmpty() }?.let { "%.0f".format(median(it)) } ?: "–",
                            easy.size,
                        ),
                    )
                }
            }
            val cadence = ground.mapNotNull { it.meanCadence?.toDouble() }
            if (cadence.isNotEmpty()) appendLine("Median cadence on outdoor runs: %.0f spm".format(median(cadence)))
        }.trimEnd()
    }

    private fun km(r: RunEntity) = (r.distanceM ?: 0f) / 1000.0

    /** Seconds per km. */
    private fun pace(r: RunEntity): Double = km(r).let { if (it > 0) r.durationSec / it else Double.MAX_VALUE }

    private fun date(r: RunEntity) = PromptRenderer.localStart(r).toLocalDate().toString()

    private fun line(r: RunEntity) = "%s, %.2f km in %s (%s/km%s)".format(
        date(r), km(r), RunAnalysis.formatDuration(r.durationSec), RunAnalysis.formatPace(pace(r)),
        r.meanHeartRate?.let { ", avg HR %.0f".format(it) } ?: "",
    )

    private fun weightedHr(list: List<RunEntity>): Double? {
        val l = list.filter { (it.meanHeartRate ?: 0f) > 0f }
        val t = l.sumOf { it.durationSec }
        return if (t == 0L) null else l.sumOf { it.meanHeartRate!!.toDouble() * it.durationSec } / t
    }

    private fun median(values: List<Double>): Double = values.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
