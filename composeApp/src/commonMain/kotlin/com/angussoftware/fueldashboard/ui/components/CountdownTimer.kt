package com.angussoftware.fueldashboard.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.delay

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

/**
 * A composable countdown that ticks every minute.
 * Forces recomposition so the displayed time updates live.
 *
 * Usage: CountdownText(resetsAt = window.resetsAt)
 */
@Composable
fun CountdownText(
    resetsAt: Long?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    if (resetsAt == null) return
    // Tick every 30 seconds to update the countdown
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = epochMillis()
            delay(30_000L)
        }
    }
    androidx.compose.material3.Text(
        text = formatCountdown(resetsAt),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
