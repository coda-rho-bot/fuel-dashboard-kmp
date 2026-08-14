package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.presentation.MeteredUsageDisplay
import kotlin.math.roundToInt

/**
 * Metered token usage — exact numbers reported by usage sources (e.g. the
 * Letta runs connector), grouped by agent and by model. Complements the
 * inferred drain rates (gauge-drop attribution) with directly measured data.
 */
@Composable
fun MeteredUsagePanel(
    bySource24h: List<MeteredUsageDisplay>,
    byModel24h: List<MeteredUsageDisplay>,
    bySource7d: List<MeteredUsageDisplay>,
    byModel7d: List<MeteredUsageDisplay>,
    modifier: Modifier = Modifier,
) {
    if (bySource24h.isEmpty() && bySource7d.isEmpty()) return

    var window7d by remember { mutableStateOf(false) }
    val bySource = if (window7d) bySource7d else bySource24h
    val byModel = if (window7d) byModel7d else byModel24h

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Metered Usage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            HelpIcon("Exact token counts metered from usage sources (agent runs), not inferred from gauge drops")
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = !window7d,
                onClick = { window7d = false },
                label = { Text("24h") },
            )
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = window7d,
                onClick = { window7d = true },
                label = { Text("7d") },
            )
        }
        Spacer(Modifier.height(8.dp))

        Text(
            text = "By agent",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        val maxSourceTokens = (bySource.maxOfOrNull { it.inputTokens + it.outputTokens } ?: 1L).coerceAtLeast(1)
        bySource.forEach { MeteredUsageRow(it, maxSourceTokens, showCost = false) }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "By model",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        val maxModelTokens = (byModel.maxOfOrNull { it.inputTokens + it.outputTokens } ?: 1L).coerceAtLeast(1)
        byModel.forEach { MeteredUsageRow(it, maxModelTokens, showCost = true) }
    }
}

@Composable
private fun MeteredUsageRow(
    usage: MeteredUsageDisplay,
    maxTokens: Long,
    showCost: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = usage.label,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = buildString {
                    append("${formatTokens(usage.inputTokens)} in / ${formatTokens(usage.outputTokens)} out")
                    append("  ·  ${usage.requestCount} req")
                    usage.creditCost?.let { append("  ·  ${formatCredits(it)} cr") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Proportional bar (input vs output segments)
        val inputFraction = (usage.inputTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
        val outputFraction = (usage.outputTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(inputFraction)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(outputFraction)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatCredits(credits: Double): String =
    if (credits >= 1_000_000) "${(credits / 1_000_000).roundToInt()}M" else credits.roundToInt().toString()
