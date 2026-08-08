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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun DecisionLog(
    decisions: List<Decision>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Decision History",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        decisions.forEach { decision ->
            DecisionRow(decision)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun DecisionRow(decision: Decision) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        // Left: timestamp + agent
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTimestamp(decision.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = shortAgentName(decision.agentId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = decision.modelHandle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Right: tier badge + reason
        Column(modifier = Modifier.weight(1.2f)) {
            TierBadge(tier = decision.tier)
            Spacer(Modifier.height(2.dp))
            Text(
                text = decision.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TierBadge(tier: String) {
    val (bg, fg) = tierColors(tier)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tier,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun tierColors(tier: String): Pair< androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val surface = androidx.compose.ui.graphics.Color.Unspecified
    val onSurface = androidx.compose.ui.graphics.Color.Unspecified
    // Use M3 container colors as fallback — actual colors resolved at composition
    return when (tier.lowercase()) {
        "high", "premium" -> Pair(
            androidx.compose.ui.graphics.Color(0xFF1B5E20),  // green-900
            androidx.compose.ui.graphics.Color(0xFFC8E6C9),  // green-100
        )
        "medium" -> Pair(
            androidx.compose.ui.graphics.Color(0xFFE65100),  // orange-900
            androidx.compose.ui.graphics.Color(0xFFFFE0B2),  // orange-100
        )
        "low", "budget" -> Pair(
            androidx.compose.ui.graphics.Color(0xFF424242),  // grey-800
            androidx.compose.ui.graphics.Color(0xFFE0E0E0),  // grey-300
        )
        else -> Pair(
            androidx.compose.ui.graphics.Color(0xFF1A237E),  // indigo-900
            androidx.compose.ui.graphics.Color(0xFFC5CAE9),  // indigo-100
        )
    }
}

private fun shortAgentName(agentId: String): String {
    // agent-c51de213-2275-4d1d-9ed4-8ccfb7047e52 -> c51de213
    val parts = agentId.split("-")
    return if (parts.size >= 2) parts[1].take(8) else agentId.take(12)
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs == 0L) return "--"
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}:${local.second.toString().padStart(2, '0')}"
}
