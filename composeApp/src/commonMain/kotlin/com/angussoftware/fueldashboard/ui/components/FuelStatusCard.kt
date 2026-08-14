package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.presentation.FuelProjection
import com.angussoftware.fueldashboard.presentation.ProviderBurnRateDisplay
import com.angussoftware.fueldashboard.presentation.QuotaType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Type-aware fuel status card.
 *
 * Instead of a single misleading "big number", shows:
 * - Rate windows (z.ai, Letta daily): informational gauges, self-healing
 * - Credit pools (Letta credits, Junie): budget bars, finite resources
 *
 * Rate windows hitting 0% just means throttling — they recover.
 * Credit pools hitting 0 means you're done until refill.
 */
@Composable
fun FuelStatusCard(
    projection: FuelProjection?,
    showHelp: Boolean,
    fuelHistory: List<Double> = emptyList(),
    providerBurnRates: List<ProviderBurnRateDisplay> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
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
                    fontWeight = FontWeight.Bold,
                )
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("Rate windows self-heal on a timer. Credit pools are finite budgets that deplete until refill.")
                }
            }
            Text(
                text = "${projection?.activeAgentCount ?: 0} agents",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (providerBurnRates.isEmpty()) {
            Text(
                text = "Collecting data — need at least 10 minutes of polling",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // Split providers by quota type
        val rateWindows = providerBurnRates.filter {
            it.quotaType == QuotaType.RATE_WINDOW
        }
        val creditPools = providerBurnRates.filter {
            it.quotaType == QuotaType.CREDIT_POOL || it.quotaType == QuotaType.SPEND_ONLY
        }

        // Rate windows section
        if (rateWindows.isNotEmpty()) {
            Text(
                text = "Rate Windows",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            rateWindows.forEach { br -> RateWindowRow(br) }
        }

        // Credit pools section
        if (creditPools.isNotEmpty()) {
            if (rateWindows.isNotEmpty()) Spacer(Modifier.height(12.dp))
            Text(
                text = "Credit Pools",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            creditPools.forEach { br -> CreditPoolRow(br) }
        }

        // Active models footer
        if (projection?.activeModels?.isNotEmpty() == true) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Models: ${projection.activeModels.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RateWindowRow(br: ProviderBurnRateDisplay) {
    val pct = br.currentPct?.roundToInt()
    val gaugeColor = when {
        pct == null -> MaterialTheme.colorScheme.onSurfaceVariant
        pct < 15 -> MaterialTheme.colorScheme.error
        pct < 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = br.providerName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${pct ?: "--"}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = gaugeColor,
                )
                // Sparkline
                if (br.history.size >= 3) {
                    Spacer(Modifier.width(8.dp))
                    FuelSparkline(
                        values = br.history,
                        color = gaugeColor,
                        modifier = Modifier.height(20.dp).width(80.dp),
                    )
                }
            }
        }

        // Info line — NOT alarming. Rate windows self-heal.
        val infoText = if (br.burnRatePerHr != null && br.burnRatePerHr > 0 && pct != null && pct < 30) {
            "Burning ${formatRate(br.burnRatePerHr)}%/hr — slides in ${formatHours(br.hoursUntilReset)}"
        } else if (br.burnRatePerHr != null && br.burnRatePerHr > 0) {
            "${formatRate(br.burnRatePerHr)}%/hr — resets in ${formatHours(br.hoursUntilReset)}"
        } else {
            "Resets in ${formatHours(br.hoursUntilReset)}"
        }
        Text(
            text = infoText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreditPoolRow(br: ProviderBurnRateDisplay) {
    val pct = br.currentPct?.roundToInt()
    val isLow = pct != null && pct < 25
    val isCritical = pct != null && pct < 10
    val barColor = when {
        isCritical -> MaterialTheme.colorScheme.error
        isLow -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = br.providerName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${pct ?: "--"}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = barColor,
            )
        }

        // Budget bar
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { ((pct ?: 0) / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = barColor,
            trackColor = barColor.copy(alpha = 0.15f),
        )

        // Projection — alarming for credit pools
        Spacer(Modifier.height(2.dp))
        val projText = if (br.burnRatePerHr != null && br.burnRatePerHr > 0 && br.hoursUntilExhaustion != null) {
            val days = br.hoursUntilExhaustion / 24.0
            if (br.quotaType == QuotaType.SPEND_ONLY) {
                "Depletes in ${formatDays(days)} — no automatic refill"
            } else {
                "Depletes in ${formatDays(days)} — refills in ${formatHours(br.hoursUntilReset)}"
            }
        } else if (br.quotaType == QuotaType.SPEND_ONLY) {
            "Finite budget — no automatic refill"
        } else {
            "Refills in ${formatHours(br.hoursUntilReset)}"
        }
        Text(
            text = projText,
            style = MaterialTheme.typography.labelSmall,
            color = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isLow) FontWeight.Bold else FontWeight.Normal,
        )
        if (isCritical) {
            Text(
                text = "⚠ Critical — budget nearly exhausted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FuelSparkline(
    values: List<Double>,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).coerceAtLeast(1.0)
        val stepX = size.width / (values.size - 1)
        val path = Path().apply {
            for (i in values.indices) {
                val x = i * stepX
                val y = size.height - ((values[i] - min) / range).toFloat() * size.height
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path, color = color, style = Stroke(width = 2f))
    }
}

private fun formatRate(rate: Double): String =
    if (abs(rate - rate.roundToInt()) < 0.05) rate.roundToInt().toString() else "%.1f".format(rate)

private fun formatHours(hours: Double): String = when {
    hours < 1 -> "${(hours * 60).roundToInt()} min"
    hours < 48 -> "${hours.roundToInt()}h"
    else -> "${(hours / 24).roundToInt()}d"
}

private fun formatDays(days: Double): String = when {
    days < 1 -> "${(days * 24).roundToInt()} hours"
    days < 30 -> "${days.roundToInt()} days"
    else -> "${(days / 7).roundToInt()} weeks"
}
