package com.hifilauncher.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Virtual scroll wheel. Only the ring reacts to rotation; the hub is a button.
 * The dial marks rotate with the finger and are spaced like the mode's tick angle,
 * so what you see matches what you feel.
 *
 * Callbacks receive signed steps (positive = clockwise). [onRelease] fires when the
 * finger lifts, which lets callers commit a pending action (e.g. a multi-track skip).
 */
@Composable
fun VirtualWheel(
    mode: WheelMode,
    isPlaying: Boolean,
    onSteps: (Int) -> Unit,
    onRelease: () -> Unit,
    onHubClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 240.dp,
    hubRatio: Float = 0.44f,
) {
    val context = LocalContext.current
    val haptics = remember { WheelHaptics(context) }
    val stepsCb by rememberUpdatedState(onSteps)
    val releaseCb by rememberUpdatedState(onRelease)
    var rotationDeg by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .size(diameter)
            .pointerInput(mode, hubRatio) {
                val outer = size.width / 2f
                val hubR = outer * hubRatio
                val engine = WheelEngine(mode, deadRadius = hubR * 0.85f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val dx = down.position.x - outer
                    val dy = down.position.y - outer
                    val r = hypot(dx, dy)
                    // Touches on the hub or outside the ring are not wheel gestures.
                    if (r < hubR || r > outer) return@awaitEachGesture
                    down.consume()
                    engine.begin(dx, dy, down.uptimeMillis)
                    while (true) {
                        val change = awaitPointerEvent().changes
                            .firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val steps = engine.move(
                            change.position.x - outer,
                            change.position.y - outer,
                            change.uptimeMillis,
                        )
                        rotationDeg += Math.toDegrees(engine.lastDeltaRad.toDouble()).toFloat()
                        if (steps != 0) {
                            haptics.tick(strong = abs(steps) > 1)
                            stepsCb(steps)
                        }
                        change.consume()
                    }
                    releaseCb()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawWheel(rotationDeg, mode.tickDeg, hubRatio) }
        Box(
            modifier = Modifier
                .size(diameter * hubRatio)
                .clip(CircleShape)
                .background(HiFi.Hub)
                .clickable(onClick = onHubClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(diameter * hubRatio * 0.45f)) {
                drawTransportIcon(if (isPlaying) IconKind.Pause else IconKind.Play, HiFi.Amber)
            }
        }
    }
}

private fun DrawScope.drawWheel(rotationDeg: Float, tickDeg: Float, hubRatio: Float) {
    val c = center
    val rOuter = size.minDimension / 2f

    // Static bezel: a conic gradient reads as brushed aluminium.
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(
                Color(0xFF1A1B1E), Color(0xFF282A2E), Color(0xFF16171A),
                Color(0xFF282A2E), Color(0xFF1A1B1E),
            ),
            center = c,
        ),
        radius = rOuter,
        center = c,
    )
    drawCircle(
        color = HiFi.ChassisEdge,
        radius = rOuter - 1.dp.toPx(),
        center = c,
        style = Stroke(1.dp.toPx()),
    )
    drawCircle(HiFi.Groove, radius = rOuter * hubRatio * 1.14f, center = c)

    // Dial marks rotate with the finger. Mark 0 is the amber index.
    val n = (360f / tickDeg).toInt()
    rotate(rotationDeg, pivot = c) {
        for (i in 0 until n) {
            val a = i * tickDeg * PI / 180.0
            val major = i == 0
            val r1 = rOuter * (if (major) 0.80f else 0.88f)
            val r2 = rOuter * 0.96f
            drawLine(
                color = if (major) HiFi.Amber else HiFi.Mark,
                start = Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()),
                end = Offset(c.x + (cos(a) * r2).toFloat(), c.y + (sin(a) * r2).toFloat()),
                strokeWidth = (if (major) 3.dp else 2.dp).toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Short, light vibration per tick. Rate-limited so a fast spin doesn't turn into a buzz. */
class WheelHaptics(context: Context) {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var lastMs = 0L

    @Suppress("DEPRECATION")
    fun tick(strong: Boolean = false) {
        val v = vibrator ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastMs < MIN_GAP_MS) return
        lastMs = now
        val ms = if (strong) STRONG_MS else SOFT_MS
        if (Build.VERSION.SDK_INT >= 26) {
            // Default amplitude: the U Ultra's motor may not support amplitude control.
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(ms)
        }
    }

    private companion object {
        const val MIN_GAP_MS = 18L
        const val SOFT_MS = 9L
        const val STRONG_MS = 16L
    }
}

internal enum class IconKind { Play, Pause, Prev, Next }

internal fun DrawScope.drawTransportIcon(kind: IconKind, color: Color) {
    val w = size.width
    val h = size.height
    // Triangle with a vertical base at x0 and its tip at x1 (fractions of the icon box).
    fun tri(x0: Float, x1: Float) = Path().apply {
        moveTo(w * x0, h * 0.28f)
        lineTo(w * x0, h * 0.72f)
        lineTo(w * x1, h * 0.50f)
        close()
    }
    when (kind) {
        IconKind.Play -> drawPath(tri(0.34f, 0.76f), color)
        IconKind.Pause -> {
            drawRect(color, Offset(w * 0.30f, h * 0.26f), Size(w * 0.15f, h * 0.48f))
            drawRect(color, Offset(w * 0.55f, h * 0.26f), Size(w * 0.15f, h * 0.48f))
        }
        IconKind.Next -> {
            drawPath(tri(0.26f, 0.66f), color)
            drawRect(color, Offset(w * 0.68f, h * 0.28f), Size(w * 0.08f, h * 0.44f))
        }
        IconKind.Prev -> {
            drawPath(tri(0.74f, 0.34f), color)
            drawRect(color, Offset(w * 0.24f, h * 0.28f), Size(w * 0.08f, h * 0.44f))
        }
    }
}
