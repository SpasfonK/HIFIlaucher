package com.hifilauncher.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Maps the absolute angular speed (rad/s) to a step multiplier. */
fun interface AccelCurve {
    fun multiplier(omega: Float): Int
}

/**
 * What the wheel drives. [tickDeg] is the angle that produces one step, so the
 * "feel" of each mode is tuned here and nowhere else. Speeds are in rad/s
 * (one turn per second is about 6.3 rad/s).
 */
enum class WheelMode(val label: String, val tickDeg: Float, val curve: AccelCurve) {
    VOLUME("Volume", 10f, AccelCurve { w -> if (w < 10f) 1 else 2 }),
    SKIP("Skip", 30f, AccelCurve { w -> if (w < 8f) 1 else if (w < 16f) 2 else 5 }),
    LIST("List", 18f, AccelCurve { w -> if (w < 6f) 1 else if (w < 14f) 3 else 8 }),
}

private val PI_F = PI.toFloat()
private val TWO_PI = (2 * PI).toFloat()

/**
 * Pure Kotlin (no Android or Compose types) so it can be unit-tested on the JVM.
 *
 * Coordinates are relative to the wheel centre, in screen axes (y points down),
 * so a positive angle is clockwise: clockwise = next / volume up.
 *
 * Usage: begin() on touch down, then move() on every pointer event.
 * move() returns the signed number of steps produced by that event (0 most of
 * the time); [lastDeltaRad] is the rotation to apply to the on-screen dial.
 */
class WheelEngine(
    private val tickRad: Float,
    private val curve: AccelCurve,
    private val deadRadius: Float = 0f,
) {
    constructor(mode: WheelMode, deadRadius: Float = 0f) :
        this((mode.tickDeg * PI / 180.0).toFloat(), mode.curve, deadRadius)

    var lastDeltaRad = 0f
        private set

    private var lastAngle = 0f
    private var lastT = 0L
    private var accum = 0f
    private var omega = 0f
    private var armed = false

    fun begin(x: Float, y: Float, t: Long) {
        accum = 0f
        omega = 0f
        lastDeltaRad = 0f
        lastT = t
        lastAngle = atan2(y, x)
        armed = hypot(x, y) >= deadRadius
    }

    fun move(x: Float, y: Float, t: Long): Int {
        lastDeltaRad = 0f

        // Near the centre the angle is mostly noise: stop tracking, re-arm on exit
        // without producing a jump.
        if (hypot(x, y) < deadRadius) {
            armed = false
            return 0
        }
        val a = atan2(y, x)
        if (!armed) {
            armed = true
            lastAngle = a
            lastT = t
            omega = 0f
            return 0
        }

        // Shortest signed angular difference, safe across the +/- pi seam.
        var d = a - lastAngle
        if (d > PI_F) d -= TWO_PI else if (d < -PI_F) d += TWO_PI
        lastAngle = a

        // Floor dt at 4 ms: bursts of near-simultaneous events would otherwise
        // produce absurd instantaneous speeds.
        val dt = (t - lastT).coerceAtLeast(4L) / 1000f
        lastT = t

        // Exponential moving average of the speed (smooths touch jitter).
        omega = omega * 0.7f + (d / dt) * 0.3f
        lastDeltaRad = d
        accum += d

        // Truncates toward zero. The remainder stays in accum, so reversing
        // direction first cancels the leftover angle (natural hysteresis).
        val ticks = (accum / tickRad).toInt()
        if (ticks == 0) return 0
        accum -= ticks * tickRad
        return ticks * curve.multiplier(abs(omega))
    }
}
