package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.presentation.FuelProjection
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Replaces the fake RecommendationBanner with honest fuel status:
 * current gauge, real burn rate, projected exhaustion, and headroom.
 * No fake "recommended model" — just real math from real data.
 */
@Composable
fun FuelStatusCard(
    projection: FuelProjection?,
    showHelp: Boolean,
    modifier: Modifier = Modifier,
) {
    if (projection == null) {
        // Not enough data yet — show a placeholder
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Fuel Status",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("Real-time fuel tracking. Collecting data...")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Collecting data — need at least 10 minutes of polling to compute burn rate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val gaugeColor = when {
        projection.willMakeIt && projection.projectedRemainingAtReset > 20 ->
            MaterialTheme.colorScheme.primaryContainer
        projection.willMakeIt ->
            MaterialTheme.colorScheme.tertiaryContainer
        else ->
            MaterialTheme.colorScheme.errorContainer
    }
    val onGaugeColor = when {
        projection.willMakeIt && projection.projectedRemainingAtReset > 20 ->
            MaterialTheme.colorScheme.onPrimaryContainer
        projection.willMakeIt ->
            MaterialTheme.colorScheme.onTertiaryContainer
        else ->
            MaterialTheme.colorScheme.onErrorContainer
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(gaugeColor)
            .padding(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Fuel Status",
                    style = MaterialTheme.typography.titleSmall,
                    color = onGaugeColor,
                    fontWeight = FontWeight.Bold,
                )
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("Real burn rate computed from actual gauge data")
                }
            }
            Text(
                text = "${projection.activeAgentCount} agents active",
                style = MaterialTheme.typography.bodySmall,
                color = onGaugeColor,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Big fuel percentage
        Text(
            text = "${projection.currentPct.roundToInt()}%",
            style = MaterialTheme.typography.displaySmall,
            color = onGaugeColor,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(8.dp))

        // Burn rate
        if (projection.burnRatePerHr != null && projection.burnRatePerHr > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Burn rate",
                    style = MaterialTheme.typography.bodySmall,
                    color = onGaugeColor,
                )
                Text(
                    text = "${formatRate(projection.burnRatePerHr)}% / hr",
                    style = MaterialTheme.typography.bodySmall,
                    color = onGaugeColor,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(4.dp))

            // Projection: exhaustion vs reset
            val projectionText = projection.hoursUntilExhaustion?.let { hrs ->
                if (projection.willMakeIt) {
                    val headroom = projection.projectedRemainingAtReset.roundToInt()
                    "Resets in ${formatHours(projection.hoursUntilReset)} · ${headroom}% headroom at reset"
                } else {
                    "Exhausts in ${formatHours(hrs)} — resets in ${formatHours(projection.hoursUntilReset)}"
                }
            } ?: "Not burning fuel currently"

            Text(
                text = projectionText,
                style = MaterialTheme.typography.bodySmall,
                color = onGaugeColor,
                fontWeight = FontWeight.Medium,
            )

            // Warning if won't make it
            if (!projection.willMakeIt) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠ Will run out before reset at current burn rate",
                    style = MaterialTheme.typography.bodySmall,
                    color = onGaugeColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Text(
                text = "Resets in ${formatHours(projection.hoursUntilReset)}",
                style = MaterialTheme.typography.bodySmall,
                color = onGaugeColor,
            )
        }

        // Active models
        if (projection.activeModels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Models: ${projection.activeModels.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = onGaugeColor,
            )
        }

        // Fuel gauge bar
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (projection.currentPct / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = onGaugeColor,
            trackColor = onGaugeColor.copy(alpha = 0.2f),
        )
    }
}

private fun formatRate(rate: Double): String {
    return if (abs(rate - rate.roundToInt()) < 0.05) {
        rate.roundToInt().toString()
    } else {
        "%.1f".format(rate)
    }
}

private fun formatHours(hours: Double): String {
    return when {
        hours < 1 -> "${(hours * 60).roundToInt()} min"
        hours < 10 -> "${hours.roundToInt()}h"
        else -> "${hours.roundToInt()}h"
    }
}
