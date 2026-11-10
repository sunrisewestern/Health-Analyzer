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
    fun Calendar(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 2.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.14f, h * 0.20f),
                size = Size(w * 0.72f, h * 0.66f),
                cornerRadius = CornerRadius(w * 0.12f),
                style = Stroke(width = sw),
            )
            drawLine(tint, Offset(w * 0.14f, h * 0.42f), Offset(w * 0.86f, h * 0.42f), strokeWidth = sw)
            drawLine(tint, Offset(w * 0.34f, h * 0.10f), Offset(w * 0.34f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.66f, h * 0.10f), Offset(w * 0.66f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
            val dot = sw * 0.75f
            drawCircle(tint, dot, Offset(w * 0.36f, h * 0.58f))
            drawCircle(tint, dot, Offset(w * 0.64f, h * 0.58f))
            drawCircle(tint, dot, Offset(w * 0.36f, h * 0.73f))
        }
    }

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

    @Composable
    fun User(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 2.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            drawCircle(
                color = tint,
                radius = w * 0.22f,
                center = Offset(w * 0.50f, h * 0.32f),
                style = Stroke(width = sw),
            )

            val path = Path().apply {
                moveTo(w * 0.16f, h * 0.84f)
                cubicTo(
                    w * 0.16f, h * 0.60f,
                    w * 0.32f, h * 0.58f,
                    w * 0.50f, h * 0.58f,
                )
                cubicTo(
                    w * 0.68f, h * 0.58f,
                    w * 0.84f, h * 0.60f,
                    w * 0.84f, h * 0.84f,
                )
            }
            drawPath(path, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))
        }
    }

    @Composable
    fun Database(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 1.8.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            drawOval(
                color = tint,
                topLeft = Offset(w * 0.20f, h * 0.14f),
                size = Size(w * 0.60f, h * 0.20f),
                style = Stroke(width = sw),
            )

            val midPath = Path().apply {
                moveTo(w * 0.20f, h * 0.46f)
                cubicTo(
                    w * 0.20f, h * 0.58f,
                    w * 0.80f, h * 0.58f,
                    w * 0.80f, h * 0.46f,
                )
            }
            drawPath(midPath, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))

            val bodyPath = Path().apply {
                moveTo(w * 0.20f, h * 0.24f)
                lineTo(w * 0.20f, h * 0.72f)
                cubicTo(
                    w * 0.20f, h * 0.84f,
                    w * 0.80f, h * 0.84f,
                    w * 0.80f, h * 0.72f,
                )
                lineTo(w * 0.80f, h * 0.24f)
            }
            drawPath(bodyPath, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Cloud(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 1.8.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            val path = Path().apply {
                moveTo(w * 0.24f, h * 0.76f)
                lineTo(w * 0.76f, h * 0.76f)
                cubicTo(w * 0.90f, h * 0.76f, w * 0.94f, h * 0.62f, w * 0.84f, h * 0.52f)
                cubicTo(w * 0.86f, h * 0.36f, w * 0.72f, h * 0.26f, w * 0.58f, h * 0.32f)
                cubicTo(w * 0.50f, h * 0.20f, w * 0.34f, h * 0.24f, w * 0.28f, h * 0.38f)
                cubicTo(w * 0.12f, h * 0.42f, w * 0.10f, h * 0.64f, w * 0.24f, h * 0.76f)
                close()
            }
            drawPath(path, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Book(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 1.8.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            val path = Path().apply {
                moveTo(w * 0.50f, h * 0.28f)
                lineTo(w * 0.50f, h * 0.80f)

                moveTo(w * 0.50f, h * 0.28f)
                cubicTo(w * 0.38f, h * 0.22f, w * 0.24f, h * 0.22f, w * 0.14f, h * 0.26f)
                lineTo(w * 0.14f, h * 0.74f)
                cubicTo(w * 0.24f, h * 0.70f, w * 0.38f, h * 0.70f, w * 0.50f, h * 0.76f)

                moveTo(w * 0.50f, h * 0.28f)
                cubicTo(w * 0.62f, h * 0.22f, w * 0.76f, h * 0.22f, w * 0.86f, h * 0.26f)
                lineTo(w * 0.86f, h * 0.74f)
                cubicTo(w * 0.76f, h * 0.70f, w * 0.62f, h * 0.70f, w * 0.50f, h * 0.76f)
            }
            drawPath(path, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Palette(
        modifier: Modifier = Modifier.size(20.dp),
        tint: Color = LocalContentColor.current,
        strokeWidth: Dp = 1.8.dp,
    ) {
        Canvas(modifier = modifier) {
            val sw = strokeWidth.toPx()
            val w = size.width
            val h = size.height

            // Outer palette circle with thumb notch
            drawCircle(
                color = tint,
                radius = w * 0.38f,
                center = Offset(w * 0.50f, h * 0.50f),
                style = Stroke(width = sw),
            )

            // Paint dots inside palette
            drawCircle(color = tint, radius = w * 0.065f, center = Offset(w * 0.35f, h * 0.38f))
            drawCircle(color = tint, radius = w * 0.065f, center = Offset(w * 0.50f, h * 0.30f))
            drawCircle(color = tint, radius = w * 0.065f, center = Offset(w * 0.65f, h * 0.38f))
            drawCircle(color = tint, radius = w * 0.075f, center = Offset(w * 0.62f, h * 0.62f), style = Stroke(width = sw * 0.85f))
        }
    }
}
