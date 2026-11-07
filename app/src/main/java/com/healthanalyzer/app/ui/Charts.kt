package com.healthanalyzer.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** One labelled value in a chart. */
data class ChartPoint(val label: String, val value: Double)

/**
 * Single-series chart card. Tap or drag across the plot to inspect a value; the readout
 * above the plot shows the selected point (or the latest one). Grid and axis labels stay
 * recessive; the series uses the theme's primary color with subtle gradient fills.
 */
@Composable
fun ChartCard(
    title: String,
    points: List<ChartPoint>,
    format: (Double) -> String,
    bars: Boolean,
    modifier: Modifier = Modifier,
    /** For pace: smaller is better, so draw faster values higher. */
    invertY: Boolean = false,
    subtitle: String? = null,
    /** Allowed grid steps (e.g. whole minutes for pace); null = 1/2/2.5/5 x 10^n. */
    tickSteps: List<Double>? = null,
    /** Short label for the y-axis grid; defaults to [format]. */
    axisFormat: (Double) -> String = format,
) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val shown = selected ?: points.lastIndex.takeIf { it >= 0 }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (points.isEmpty()) {
                Text(
                    "No data recorded for this filter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }
            Row(
                Modifier.padding(top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val p = points[shown!!]
                Text(
                    format(p.value),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    p.label + if (selected == null) " (latest)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Plot(points, axisFormat, bars, invertY, tickSteps, selected = shown, onSelect = { selected = it })
        }
    }
}

@Composable
private fun Plot(
    points: List<ChartPoint>,
    format: (Double) -> String,
    bars: Boolean,
    invertY: Boolean,
    tickSteps: List<Double>?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val series = MaterialTheme.colorScheme.primary
    val seriesFade = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current

    val values = points.map { it.value }
    val sortedValues = values.sorted()
    fun pct(q: Double) = sortedValues[((sortedValues.size - 1) * q).toInt()]
    var lo = when {
        bars -> 0.0
        values.size >= 20 -> pct(0.02)
        else -> sortedValues.first()
    }
    var hi = if (!bars && values.size >= 20) pct(0.98) else sortedValues.last()
    if (!bars) {
        val pad = max((hi - lo) * 0.1, max(abs(hi) * 0.01, 1e-6))
        lo -= pad
        hi += pad
    }
    if (hi <= lo) hi = lo + 1
    val ticks = niceTicks(lo, hi, 4, tickSteps)
    if (bars) hi = max(hi, ticks.last())

    val leftPad = with(density) { 44.dp.toPx() }
    val bottomPad = with(density) { 18.dp.toPx() }

    fun indexAt(x: Float, width: Float): Int {
        val plotW = width - leftPad
        val i = if (bars) ((x - leftPad) / plotW * points.size).toInt()
        else ((x - leftPad) / plotW * (points.size - 1) + 0.5f).toInt()
        return i.coerceIn(0, points.lastIndex)
    }

    Column {
        Canvas(
            Modifier.fillMaxWidth().height(150.dp)
                .pointerInput(points) {
                    detectTapGestures { onSelect(indexAt(it.x, size.width.toFloat())) }
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures { change, _ ->
                        onSelect(indexAt(change.position.x, size.width.toFloat()))
                    }
                },
        ) {
            val plotW = size.width - leftPad
            val plotH = size.height - bottomPad
            fun y(v: Double): Float {
                val t = ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                return if (invertY) plotH * t else plotH * (1 - t)
            }

            // Recessive grid with value labels on the left.
            ticks.forEach { t ->
                if (t < lo || t > hi) return@forEach
                val yy = y(t)
                drawLine(
                    grid, Offset(leftPad, yy), Offset(size.width, yy), strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    format(t), 0f, yy + 4 * density.density,
                    android.graphics.Paint().apply {
                        color = ink.toArgb()
                        textSize = labelStyle.fontSize.toPx()
                        isAntiAlias = true
                    },
                )
            }

            if (bars) {
                val slot = plotW / points.size
                val gap = 3.dp.toPx()
                val w = (slot - gap).coerceAtLeast(2f)
                val r = minOf(4.dp.toPx(), w / 2)
                points.forEachIndexed { i, p ->
                    val top = y(p.value)
                    val h = plotH - top
                    if (h <= 0f) return@forEachIndexed
                    val x = leftPad + i * slot + gap / 2
                    val color = if (selected == null || selected == i) series else series.copy(alpha = 0.35f)
                    // Rounded data end, square at the baseline.
                    drawRoundRect(color, Offset(x, top), Size(w, h), CornerRadius(r, r))
                    if (h > r) drawRect(color, Offset(x, top + r), Size(w, h - r))
                }
            } else {
                val step = if (points.size > 1) plotW / (points.size - 1) else 0f
                fun px(i: Int) = if (points.size > 1) leftPad + i * step else leftPad + plotW / 2

                // Smooth area fill under the line
                val fillPath = Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) fillPath.moveTo(px(i), plotH)
                    fillPath.lineTo(px(i), y(p.value))
                }
                fillPath.lineTo(px(points.lastIndex), plotH)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(seriesFade, Color.Transparent),
                        startY = 0f,
                        endY = plotH,
                    ),
                )

                // Line path
                val path = Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(px(i), y(p.value)) else path.lineTo(px(i), y(p.value))
                }
                drawPath(path, series, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

                if (points.size <= 40) {
                    points.forEachIndexed { i, p ->
                        drawCircle(series, 3.dp.toPx(), Offset(px(i), y(p.value)))
                    }
                }

                // Interactive cursor highlight
                selected?.let { i ->
                    val c = Offset(px(i), y(points[i].value))
                    drawLine(grid, Offset(c.x, 0f), Offset(c.x, plotH), strokeWidth = 1.dp.toPx())
                    drawCircle(surface, 7.dp.toPx(), c)
                    drawCircle(series, 4.5.dp.toPx(), c)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 44.dp, top = 4.dp)) {
            Text(points.first().label, style = labelStyle, color = ink, modifier = Modifier.weight(1f))
            if (points.size > 1) Text(points.last().label, style = labelStyle, color = ink)
        }
    }
}

/** About [count] round tick values covering [lo, hi]. */
internal fun niceTicks(lo: Double, hi: Double, count: Int, steps: List<Double>? = null): List<Double> {
    val raw = (hi - lo) / count
    val mag = Math.pow(10.0, floor(Math.log10(raw)))
    val step = steps?.firstOrNull { it >= raw } ?: steps?.lastOrNull()
        ?: listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = ceil(lo / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= hi + step * 0.001 }.toList()
        .ifEmpty { listOf(lo) }
}

/** Small headline number with a label. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
