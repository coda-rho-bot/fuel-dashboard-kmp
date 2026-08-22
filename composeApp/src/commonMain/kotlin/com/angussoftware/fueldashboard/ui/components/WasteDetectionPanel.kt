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
import androidx.compose.material3.LinearProgressIndicator
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
 * Expired-quota waste: how much quota evaporated unused when each 5h window
 * slid. A window that slides with fuel still remaining wasted that fuel —
 * it did not carry over. 100% remaining at expiry = the whole window wasted;
 * exhausted to 0% = nothing wasted.
 */
@Composable
fun WasteDetectionPanel(
    providers: List<FuelIntelligence.ProviderWaste>,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (providers.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Wasted Quota",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            HelpIcon(
                "Quota that expired unused when each window closed. A window that ends with fuel remaining wasted it — quota does not carry over. " +
                    "Window length follows each provider\u2019s own quota mechanics (z.ai 5h, Letta daily 24h, pools = refill period). " +
                    "High waste = capacity you paid for went unused.",
            )
            Spacer(Modifier.weight(1f))
            ReorderControls(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        }
        Spacer(Modifier.height(8.dp))

        providers.forEach { pw ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pw.providerName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${pw.wastedPctAvg.toInt()}% avg/day wasted",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (pw.wastedPctAvg > 60) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            pw.daily.take(7).forEach { day ->
                DailyWasteRow(day, windowMs = pw.windowMs)
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DailyWasteRow(day: FuelIntelligence.DailyWaste, windowMs: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDate(day.dayStart),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${day.wastedPctAvg.toInt()}% wasted",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        day.wastedPctAvg > 70 -> MaterialTheme.colorScheme.tertiary
                        day.wastedPctAvg > 40 -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text(
                text = "${day.windows} × ${formatWindow(windowMs)}" +
                    (if (day.estimated > 0) " · ${day.estimated} est." else "") +
                    (if (day.anyExhausted) " · hit 0% at least once" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatWindow(windowMs: Long): String =
    if (windowMs >= 24 * 3_600_000L) "${(windowMs / (24 * 3_600_000L))}d window"
    else "${(windowMs / 3_600_000L)}h window"

private fun formatDate(epochMs: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.monthNumber.toString().padStart(2, '0')}-${local.dayOfMonth.toString().padStart(2, '0')}"
}
