package com.angussoftware.fueldashboard.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-math tests for the grid view gauges: needle angle mapping and
 * hourglass sand fraction.
 */
class GridGaugeMathTest {

    @Test
    fun needle_emptyPointsLeft_fullPointsRight() {
        assertEquals(150f, needleAngleDeg(0))    // E — down-left
        assertEquals(30f, needleAngleDeg(100))   // F — down-right
        assertEquals(90f, needleAngleDeg(50))    // half — straight up
    }

    @Test
    fun needle_clampsOutOfRange() {
        assertEquals(150f, needleAngleDeg(-5))
        assertEquals(30f, needleAngleDeg(150))
        assertEquals(150f, needleAngleDeg(null)) // no data reads as empty
    }

    @Test
    fun sand_fullWindowAtStart() {
        val now = 1_000_000L
        val windowHours = 5.0
        val resetsAt = now + (5.0 * 3_600_000).toLong()
        assertEquals(1f, sandFraction(resetsAt, windowHours, now)!!, 0.01f)
    }

    @Test
    fun sand_halfway() {
        val now = 1_000_000L
        val resetsAt = now + (2.5 * 3_600_000).toLong() // half of 5h left
        assertEquals(0.5f, sandFraction(resetsAt, 5.0, now)!!, 0.001f)
    }

    @Test
    fun sand_expiredIsZero_nullWithoutReset() {
        assertEquals(0f, sandFraction(999L, 5.0, 1_000L)!!)
        assertNull(sandFraction(null, 5.0))
        assertNull(sandFraction(1_000L, 0.0, 1L)) // zero window = no countdown
    }

    @Test
    fun sand_clampedToUnit() {
        // Window LONGER than time remaining would give >1 — clamp.
        val now = 1_000_000L
        val f = sandFraction(now + 3_600_000L, 0.5, now) // 1h left of 0.5h window
        assertTrue(f != null && f <= 1f)
    }
}
