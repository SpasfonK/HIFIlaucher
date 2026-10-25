package com.hifilauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

// Goes in src/test/java/com/hifilauncher/ui/. Needs testImplementation("junit:junit:4.13.2").
class WheelEngineTest {

    private val flat = AccelCurve { 1 }
    private fun engine(deadRadius: Float = 0f) =
        WheelEngine(Math.toRadians(15.0).toFloat(), flat, deadRadius)

    private fun px(deg: Float, r: Float = 100f) = (r * cos(Math.toRadians(deg.toDouble()))).toFloat()
    private fun py(deg: Float, r: Float = 100f) = (r * sin(Math.toRadians(deg.toDouble()))).toFloat()

    /** Sweeps from one angle to another and returns the total steps produced. */
    private fun sweep(e: WheelEngine, from: Float, to: Float, stepDeg: Float, dtMs: Long = 100): Int {
        var t = 0L
        e.begin(px(from), py(from), t)
        var total = 0
        var a = from
        val dir = if (to >= from) 1 else -1
        while ((to - a) * dir > 0.001f) {
            a += stepDeg * dir
            t += dtMs
            total += e.move(px(a), py(a), t)
        }
        return total
    }

    @Test
    fun clockwise_sweep_gives_positive_steps() {
        // 95 degrees / 15 per tick = 6.33 -> 6 steps
        assertEquals(6, sweep(engine(), 0f, 95f, 5f))
    }

    @Test
    fun counter_clockwise_sweep_gives_negative_steps() {
        assertEquals(-6, sweep(engine(), 0f, -95f, 5f))
    }

    @Test
    fun crossing_the_pi_seam_does_not_jump() {
        // 170 -> 195 degrees crosses +/-180: only 25 degrees of real rotation = 1 step
        assertEquals(1, sweep(engine(), 170f, 195f, 5f))
    }

    @Test
    fun dead_zone_produces_nothing_and_rearms_without_a_jump() {
        val e = engine(deadRadius = 50f)
        e.begin(100f, 0f, 0)
        assertEquals(0, e.move(10f, 10f, 100))
        assertEquals(0, e.move(-10f, 10f, 200))
        assertEquals(0, e.move(0f, 100f, 300)) // re-arms on exit, must not count 90 degrees
    }

    @Test
    fun fast_spin_is_accelerated() {
        val slow = sweep(WheelEngine(WheelMode.SKIP), 0f, 95f, 5f, dtMs = 100)
        val fast = sweep(WheelEngine(WheelMode.SKIP), 0f, 95f, 5f, dtMs = 10)
        assertEquals(3, slow) // 95 / 30 = 3 ticks, multiplier 1
        assertTrue("fast=$fast should exceed slow=$slow", fast > slow)
    }
}
