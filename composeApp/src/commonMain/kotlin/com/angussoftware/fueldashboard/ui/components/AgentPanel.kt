package com.angussoftware.fueldashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Display model for an agent discovered via ACP (Agent Client Protocol).
 *
 * This is a UI-facing data class decoupled from the wire protocol —
 * the ViewModel maps discovered agents into this shape.
 */
data class AcpAgentDisplay(
    val id: String,
    val name: String,
    val currentModel: String?,
    val availableModels: List<String>,
    val currentMode: String?,
    val availableModes: List<String>,
    val status: String, // "connected", "disconnected", "idle", "thinking"
    val capabilities: List<String> = emptyList(),
)

/**
 * Panel that displays ACP-discovered agents with their models, modes, and status.
 *
 * @param agents       list of agents to render
 * @param onModelChange invoked when the user picks a different model for an agent
 * @param onModeChange  invoked when the user picks a different mode for an agent
 * @param modifier      outer modifier
 */
@Composable
fun AgentPanel(
    agents: List<AcpAgentDisplay>,
    onModelChange: (agentId: String, model: String) -> Unit,
    onModeChange: (agentId: String, mode: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Agents",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        if (agents.isEmpty()) {
            Text(
                text = "No agents discovered. Make sure your agent framework is running.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        agents.forEach { agent ->
            AgentCard(
                agent = agent,
                onModelChange = onModelChange,
                onModeChange = onModeChange,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Agent Card
// ---------------------------------------------------------------------------

@Composable
private fun AgentCard(
    agent: AcpAgentDisplay,
    onModelChange: (agentId: String, model: String) -> Unit,
    onModeChange: (agentId: String, mode: String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // --- Header row: name + status dot ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StatusDot(status = agent.status)
            }

            Spacer(Modifier.height(8.dp))

            // --- Model row ---
            ModelRow(
                currentModel = agent.currentModel,
                availableModels = agent.availableModels,
                onModelSelected = { model -> onModelChange(agent.id, model) },
            )

            // --- Mode row (only if modes are available) ---
            if (agent.availableModes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ModeRow(
                    currentMode = agent.currentMode,
                    availableModes = agent.availableModes,
                    onModeSelected = { mode -> onModeChange(agent.id, mode) },
                )
            }

            // --- Capabilities row (only if capabilities exist) ---
            if (agent.capabilities.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                CapabilitiesRow(capabilities = agent.capabilities)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Status Dot
// ---------------------------------------------------------------------------

@Composable
private fun StatusDot(status: String) {
    val color = when (status.lowercase()) {
        "connected" -> Color(0xFF4CAF50) // green
        "idle" -> Color(0xFFFFA726) // amber
        "thinking" -> Color(0xFF42A5F5) // blue
        else -> MaterialTheme.colorScheme.outline // grey for disconnected
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

// ---------------------------------------------------------------------------
// Model Row (with dropdown)
// ---------------------------------------------------------------------------

@Composable
private fun ModelRow(
    currentModel: String?,
    availableModels: List<String>,
    onModelSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Model:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))

        if (availableModels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentModel ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Change model",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        },
                    )
                }
            }
        } else {
            Text(
                text = currentModel ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Mode Row (selector chips)
// ---------------------------------------------------------------------------

@Composable
private fun ModeRow(
    currentMode: String?,
    availableModes: List<String>,
    onModeSelected: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Mode:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        availableModes.forEach { mode ->
            ModeChip(
                label = mode,
                isSelected = mode == currentMode,
                onClick = { onModeSelected(mode) },
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Capabilities Row (badges)
// ---------------------------------------------------------------------------

@Composable
private fun CapabilitiesRow(capabilities: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Capabilities:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        capabilities.forEach { cap ->
            CapabilityBadge(label = cap)
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun CapabilityBadge(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
