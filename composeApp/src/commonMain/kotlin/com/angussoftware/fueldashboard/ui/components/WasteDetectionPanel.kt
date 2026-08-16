package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.presentation.FuelIntelligence
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Waste detection: hourly windows where the fuel gauge dropped while
 * metered usage showed (nearly) nothing. Unattributed drain = idle
 * polling, restart storms, or consumption the metering doesn't see.
 */
@Composable
fun WasteDetectionPanel(
    windows: List<FuelIntelligence.WasteWindow>,
    modifier: Modifier = Modifier,
) {
    if (windows.isEmpty()) return

    val flagged = windows.filter { it.unattributed }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Waste Detection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            HelpIcon("Hours where fuel dropped but metered usage was near zero — unattributed drain from idle polling, restart storms, or unmetered consumption")
        }
        Spacer(Modifier.height(8.dp))

        if (flagged.isEmpty()) {
            Text(
                text = "No unattributed drain in the last 24h — every significant drop lines up with metered usage. ✅",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "${flagged.size} hour${if (flagged.size == 1) "" else "s"} with unattributed fuel consumption:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            flagged.forEach { window ->
                WasteRow(window)
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }

        // Compact per-hour consumption summary (context, newest last)
        Spacer(Modifier.height(4.dp))
        Text(
            text = windows.joinToString("  ") { w ->
                "${formatHour(w.hourStart)} ${"%.1f".format(w.fuelConsumedPct)}%"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WasteRow(window: FuelIntelligence.WasteWindow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatHour(window.hourStart),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${"%.1f".format(window.fuelConsumedPct)}% consumed · ${formatTokens(window.meteredTokens)} metered",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "${"%.1f".format(window.avgActiveAgents)} avg active agents",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatHour(epochMs: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.toString().padStart(2, '0')}:00"
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${"%.1f".format(tokens / 1_000_000.0)}M"
    tokens >= 1_000 -> "${"%.1f".format(tokens / 1_000.0)}K"
    else -> tokens.toString()
}
