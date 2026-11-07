package com.healthanalyzer.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dedicated vector icons tailored for HealthAnalyzer:
 * Runner, AI Sparkles, Heart Pulse, Pace, Cadence, Elevation, Garmin, Samsung.
 */
object AppIcons {

    @Composable
    fun Runner(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 2.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            // Head (runner leaning forward)
            drawCircle(
                color = tint,
                radius = w * 0.11f,
                center = Offset(w * 0.62f, h * 0.20f),
            )

            // Torso & Legs & Arms Path
            val path = Path().apply {
                // Torso leaning forward
                moveTo(w * 0.58f, h * 0.32f)
                lineTo(w * 0.46f, h * 0.54f)

                // Front thigh & calf
                lineTo(w * 0.64f, h * 0.64f)
                lineTo(w * 0.60f, h * 0.88f)

                // Back thigh & calf kicking back
                moveTo(w * 0.46f, h * 0.54f)
                lineTo(w * 0.30f, h * 0.66f)
                lineTo(w * 0.18f, h * 0.60f)

                // Front arm swinging forward
                moveTo(w * 0.55f, h * 0.38f)
                lineTo(w * 0.76f, h * 0.44f)
                lineTo(w * 0.84f, h * 0.34f)

                // Back arm pumping backward
                moveTo(w * 0.55f, h * 0.38f)
                lineTo(w * 0.36f, h * 0.42f)
                lineTo(w * 0.30f, h * 0.32f)
            }

            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }

    @Composable
    fun Sparkles(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalContentColor.current,
    ) {
        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height

            // Main 4-point sparkle
            fun drawSparkle(cx: Float, cy: Float, radius: Float) {
                val p = Path().apply {
                    moveTo(cx, cy - radius)
                    cubicTo(cx, cy - radius * 0.2f, cx + radius * 0.2f, cy, cx + radius, cy)
                    cubicTo(cx + radius * 0.2f, cy, cx, cy + radius * 0.2f, cx, cy + radius)
                    cubicTo(cx, cy + radius * 0.2f, cx - radius * 0.2f, cy, cx - radius, cy)
                    cubicTo(cx - radius * 0.2f, cy, cx, cy - radius * 0.2f, cx, cy - radius)
                    close()
                }
                drawPath(p, color = tint)
            }

            drawSparkle(w * 0.45f, h * 0.45f, w * 0.38f)
            drawSparkle(w * 0.80f, h * 0.22f, w * 0.18f)
            drawSparkle(w * 0.20f, h * 0.78f, w * 0.14f)
        }
    }

    @Composable
    fun Tune(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 2.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            // Top slider
            drawLine(tint, Offset(w * 0.15f, h * 0.28f), Offset(w * 0.85f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
            drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.38f, h * 0.28f))

            // Middle slider
            drawLine(tint, Offset(w * 0.15f, h * 0.52f), Offset(w * 0.85f, h * 0.52f), strokeWidth = sw, cap = StrokeCap.Round)
            drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.65f, h * 0.52f))

            // Bottom slider
            drawLine(tint, Offset(w * 0.15f, h * 0.76f), Offset(w * 0.85f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
            drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.32f, h * 0.76f))
        }
    }

    @Composable
    fun HeartPulse(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 2.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            val p = Path().apply {
                moveTo(w * 0.08f, h * 0.50f)
                lineTo(w * 0.28f, h * 0.50f)
                lineTo(w * 0.40f, h * 0.18f)
                lineTo(w * 0.54f, h * 0.82f)
                lineTo(w * 0.66f, h * 0.38f)
                lineTo(w * 0.74f, h * 0.50f)
                lineTo(w * 0.92f, h * 0.50f)
            }
            drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Pace(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 1.8.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            // Speedometer gauge arc
            val path = Path().apply {
                moveTo(w * 0.20f, h * 0.75f)
                cubicTo(w * 0.10f, h * 0.45f, w * 0.35f, h * 0.15f, w * 0.50f, h * 0.15f)
                cubicTo(w * 0.65f, h * 0.15f, w * 0.90f, h * 0.45f, w * 0.80f, h * 0.75f)
            }
            drawPath(path, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))

            // Needle
            drawLine(tint, Offset(w * 0.50f, h * 0.60f), Offset(w * 0.68f, h * 0.36f), strokeWidth = sw * 1.2f, cap = StrokeCap.Round)
            drawCircle(tint, radius = w * 0.08f, center = Offset(w * 0.50f, h * 0.60f))
        }
    }

    @Composable
    fun MountainElevation(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 1.8.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            val p = Path().apply {
                moveTo(w * 0.10f, h * 0.80f)
                lineTo(w * 0.44f, h * 0.24f)
                lineTo(w * 0.62f, h * 0.52f)
                lineTo(w * 0.74f, h * 0.36f)
                lineTo(w * 0.92f, h * 0.80f)
            }
            drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun ChevronRight(
        modifier: Modifier = Modifier.size(16.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 2.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            val p = Path().apply {
                moveTo(w * 0.35f, h * 0.20f)
                lineTo(w * 0.65f, h * 0.50f)
                lineTo(w * 0.35f, h * 0.80f)
            }
            drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun ArrowLeft(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 2.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            drawLine(tint, Offset(w * 0.22f, h * 0.50f), Offset(w * 0.85f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
            val p = Path().apply {
                moveTo(w * 0.45f, h * 0.25f)
                lineTo(w * 0.20f, h * 0.50f)
                lineTo(w * 0.45f, h * 0.75f)
            }
            drawPath(p, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
