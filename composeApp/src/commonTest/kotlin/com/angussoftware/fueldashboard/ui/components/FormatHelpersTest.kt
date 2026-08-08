package com.angussoftware.fueldashboard.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatHelpersTest {
    @Test
    fun formatCountdownHandlesExpiredAndShortAndLongWindows() {
        val now = 10_000_000L

        assertEquals("resetting...", formatCountdown(now, now))
        assertEquals("<1m", formatCountdown(now + 59_000L, now))
        assertEquals("45m", formatCountdown(now + 45 * 60_000L, now))
        assertEquals("1h 23m", formatCountdown(now + 83 * 60_000L, now))
    }

    @Test
    fun formatLastSeenHandlesCurrentAndOlderTimestamps() {
        val now = 10_000_000L

        assertEquals("just now", formatLastSeen(now, now))
        assertEquals("5m ago", formatLastSeen(now - 5 * 60_000L, now))
        assertEquals("2h ago", formatLastSeen(now - 2 * 60 * 60_000L, now))
        assertEquals("3d ago", formatLastSeen(now - 3 * 24 * 60 * 60_000L, now))
    }
}