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
    // Tick every 30 seconds to update the countdown.
    // tick MUST be read in the composable body (not just written) to trigger recomposition.
    var tick by remember { mutableLongStateOf(epochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            tick = epochMillis()
        }
    }
    androidx.compose.material3.Text(
        text = formatCountdown(resetsAt, tick),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
