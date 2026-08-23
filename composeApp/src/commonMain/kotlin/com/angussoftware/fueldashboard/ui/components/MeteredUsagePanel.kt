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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.presentation.MeteredUsageDisplay
import com.angussoftware.fueldashboard.presentation.ConversationUsageDisplay
import com.angussoftware.fueldashboard.presentation.AgentModelUsageDisplay
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
    byConversation24h: List<ConversationUsageDisplay> = emptyList(),
    byConversation7d: List<ConversationUsageDisplay> = emptyList(),
    byAgentModel24h: List<AgentModelUsageDisplay> = emptyList(),
    byAgentModel7d: List<AgentModelUsageDisplay> = emptyList(),
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (bySource24h.isEmpty() && bySource7d.isEmpty() &&
        byConversation24h.isEmpty() && byConversation7d.isEmpty() &&
        byAgentModel24h.isEmpty() && byAgentModel7d.isEmpty()
    ) return

    var window7d by remember { mutableStateOf(false) }
    val bySource = if (window7d) bySource7d else bySource24h
    val byModel = if (window7d) byModel7d else byModel24h
    val byConversation = if (window7d) byConversation7d else byConversation24h
    val byAgentModel = if (window7d) byAgentModel7d else byAgentModel24h

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
            ReorderControls(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
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
        PagedRows(bySource) { MeteredUsageRow(it, maxSourceTokens, showCost = false) }

        if (byAgentModel.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "By agent × model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                HelpIcon("Cost-accurate breakdown: each agent's tokens split by the model that ran them (models have different credit multipliers)")
            }
            val maxAgentModelTokens = (byAgentModel.maxOfOrNull { it.inputTokens + it.outputTokens } ?: 1L).coerceAtLeast(1)
            PagedRows(byAgentModel) { AgentModelUsageRow(it, maxAgentModelTokens) }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "By model",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        val maxModelTokens = (byModel.maxOfOrNull { it.inputTokens + it.outputTokens } ?: 1L).coerceAtLeast(1)
        PagedRows(byModel) { MeteredUsageRow(it, maxModelTokens, showCost = true) }

        if (byConversation.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "By conversation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            val maxConvTokens = (byConversation.maxOfOrNull { it.inputTokens + it.outputTokens } ?: 1L).coerceAtLeast(1)
            PagedRows(byConversation) { ConversationUsageRow(it, maxConvTokens) }
        }
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
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

/** Shorten a conversation ID for display (first 12 chars). */
private fun shortConvId(id: String): String =
    if (id.length > 12) id.take(12) + "…" else id

@Composable
private fun ConversationUsageRow(
    usage: ConversationUsageDisplay,
    maxTokens: Long,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = usage.title ?: shortConvId(usage.conversationId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("${usage.agentName} · ${usage.model}")
                    append("  ·  ${formatTokens(usage.inputTokens)} in / ${formatTokens(usage.outputTokens)} out")
                    append("  ·  ${usage.requestCount} req")
                    usage.creditCost?.let { append("  ·  ${formatCredits(it)} cr") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Proportional bar
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

@Composable
private fun AgentModelUsageRow(
    usage: AgentModelUsageDisplay,
    maxTokens: Long,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${usage.agentName} · ${usage.model}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        // Proportional bar
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
