package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What counts as "quiet enough to restart the fleet".
 *
 * The asymmetry drives every case here: a false "busy" costs a delayed
 * switch, a false "quiet" costs someone's in-flight turn. So anything short
 * of complete confidence reads as not-quiet.
 */
class ClaudeCodeFleetTest {

    @Test
    fun allIdleIsQuiet() {
        val fleet = ClaudeCodeFleet(busy = 0, idle = 25, unknown = 0)
        assertTrue(fleet.isQuiet)
        assertEquals(25, fleet.total)
    }

    @Test
    fun oneBusySessionBlocks() {
        assertFalse(ClaudeCodeFleet(busy = 1, idle = 24, unknown = 0).isQuiet)
    }

    @Test
    fun anUnreadableSessionBlocks() {
        // It might be mid-turn. Guessing wrong kills that turn, so an
        // unreadable session counts against quiet rather than being ignored.
        assertFalse(ClaudeCodeFleet(busy = 0, idle = 24, unknown = 1).isQuiet)
    }

    @Test
    fun anEmptyRegistryIsNotQuiet() {
        // Zero sessions means the registry told us nothing — not that nobody
        // is working. Treating it as quiet would let a switch fire against a
        // fleet we simply failed to see.
        assertFalse(ClaudeCodeFleet().isQuiet)
        assertEquals(0, ClaudeCodeFleet().total)
    }

    @Test
    fun describeNamesEveryBucketForTheDecisionLog() {
        assertEquals(
            "busy=2 idle=20 unknown=1",
            ClaudeCodeFleet(busy = 2, idle = 20, unknown = 1).describe(),
        )
    }
}
