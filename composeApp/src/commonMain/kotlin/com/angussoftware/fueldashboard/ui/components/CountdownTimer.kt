package com.angussoftware.fueldashboard.ui.components

import com.angussoftware.fueldashboard.util.epochMillis

/**
 * Formats a countdown from now until the given epoch-ms timestamp.
 * Returns "1h 23m" or "45m" or "expired" style strings.
 */
fun formatCountdown(resetsAtEpochMs: Long): String {
    val nowMs = epochMillis()
    val diff = resetsAtEpochMs - nowMs

    if (diff <= 0L) return "resetting..."

    val totalMinutes = diff / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L

    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m"
        else -> "<1m"
    }
}
