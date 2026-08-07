package com.angussoftware.fueldashboard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.ui.rememberQrScanner
import com.angussoftware.fueldashboard.ui.supportsQrScanning
import kotlin.math.abs

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
    val framework: String? = null,
    val command: String? = null,
    val registeredAt: Long? = null,
    val lastSeen: Long? = null,
)

/**
 * Panel that displays ACP-discovered agents with their models, modes, and status.
 *
 * @param agents       list of agents to render
 * @param onModelChange invoked when the user picks a different model for an agent
 * @param onModeChange  invoked when the user picks a different mode for an agent
 * @param onRemoveAgent invoked when the user clicks the delete button on an agent card
 * @param onAddAgent    invoked when the user submits a manual agent configuration
 * @param syncData      current settings snapshot to share with another device
 * @param onImportSyncedSettings invoked when imported settings are confirmed
 * @param hasConnectedOrchestrator whether an Orchestrator provider is configured
 * @param modifier      outer modifier
 */
@Composable
fun AgentPanel(
    agents: List<AcpAgentDisplay>,
    onModelChange: (agentId: String, model: String) -> Unit,
    onModeChange: (agentId: String, mode: String) -> Unit,
    onRemoveAgent: (agentId: String) -> Unit,
    onAddAgent: (name: String, command: String, args: String) -> Unit,
    syncData: SettingsSyncData,
    onImportSyncedSettings: (SettingsSyncData) -> Unit,
    hasConnectedOrchestrator: Boolean,
    showHelp: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var showQrSyncDialog by remember { mutableStateOf(false) }
    var showImportEntryDialog by remember { mutableStateOf(false) }
    var scannedSyncData by remember { mutableStateOf<SettingsSyncData?>(null) }
    val qrScanner = rememberQrScanner { scannedText ->
        if (scannedText != null) {
            SettingsSyncData.fromJson(scannedText)?.let { parsed ->
                scannedSyncData = parsed
                showImportEntryDialog = false
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Agents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showQrSyncDialog = true }) {
                    Icon(Icons.Default.QrCode2, contentDescription = "Sync agents", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { showImportEntryDialog = true }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Import agents", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add agent", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Manually", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (showHelp) {
            Text(
                text = "Sync and Import transfer ALL settings (providers + agents + themes) between devices.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        if (agents.isEmpty()) {
            if (hasConnectedOrchestrator) {
                HelpText("Connected to server but no agents registered yet. Agents appear here when they self-register via MCP.")
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "• Agents can self-register via MCP (add this app as an MCP server in your agent config)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "• Or add an agent manually with the Add Manually button",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "• On mobile: connect to your desktop via Orchestrator provider in Settings to sync agents",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            agents.forEach { agent ->
                AgentCard(
                    agent = agent,
                    isExpanded = expandedStates[agent.id] ?: false,
                    onToggleExpand = {
                        expandedStates[agent.id] = !(expandedStates[agent.id] ?: false)
                    },
                    onModelChange = onModelChange,
                    onModeChange = onModeChange,
                    onRemoveAgent = onRemoveAgent,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (showHelp) {
            Spacer(Modifier.height(8.dp))
            McpSetupGuide()
            Spacer(Modifier.height(8.dp))
            AcpSetupGuide()
            Spacer(Modifier.height(8.dp))
            MobileSyncGuide()
        }
    }

    if (showAddDialog) {
        AddAgentDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, command, args ->
                onAddAgent(name, command, args)
                showAddDialog = false
            },
        )
    }

    if (showQrSyncDialog) {
        QrSyncDialog(
            syncData = syncData,
            onDismiss = { showQrSyncDialog = false },
        )
    }

    if (showImportEntryDialog) {
        ImportEntryDialog(
            canScanQr = supportsQrScanning,
            onScanQr = { qrScanner.launch() },
            onImportCode = { parsed -> scannedSyncData = parsed },
            onDismiss = { showImportEntryDialog = false },
        )
    }

    scannedSyncData?.let { data ->
        ImportSettingsDialog(
            syncData = data,
            onConfirm = {
                onImportSyncedSettings(data)
                scannedSyncData = null
            },
            onDismiss = { scannedSyncData = null },
        )
    }
}

@Composable
private fun McpSetupGuide() {
    var isExpanded by remember { mutableStateOf(false) }

    ExpandableSetupGuide(
        title = "How to connect agents via MCP (recommended)",
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded },
    ) {
        GuideText(
            "MCP (Model Context Protocol) is a standard way for AI agents to communicate with external tools. " +
                "Your dashboard includes a built-in MCP server that agents can connect to.",
        )
        GuideText("1. The dashboard MCP server runs at:")
        CodeExample("Desktop: http://localhost:8321/mcp\nLAN: http://[your-IP]:8321/mcp")
        GuideText("2. Add the MCP server to Letta Code settings.json mcpServers:")
        CodeExample(
            """
            {
              "mcpServers": {
                "fuel-dashboard": {
                  "url": "http://localhost:8321/mcp"
                }
              }
            }
            """.trimIndent(),
        )
        GuideText("3. For Claude Code, add the same configuration to .mcp.json or settings:")
        CodeExample(
            """
            {
              "mcpServers": {
                "fuel-dashboard": {
                  "url": "http://localhost:8321/mcp"
                }
              }
            }
            """.trimIndent(),
        )
        GuideText("4. For other agents, any MCP-compatible client can connect to the same URL.")
        GuideText(
            "5. When an agent connects, it calls the register_agent tool with its name and model, then appears in the dashboard.",
        )
        GuideText(
            "Available MCP tools for agents:\n" +
                "• register_agent (register name, model, framework)\n" +
                "• update_model (report model changes)\n" +
                "• update_status (report idle/thinking/error status)\n" +
                "• add_provider (add an LLM provider to monitor)\n" +
                "• list_providers (see configured providers)",
        )
        GuideText(
            "Available MCP resources for agents:\n" +
                "• fuel://current (read current fuel state)\n" +
                "• fuel://recommendation (read recommended model)",
        )
    }
}

@Composable
private fun AcpSetupGuide() {
    var isExpanded by remember { mutableStateOf(false) }

    ExpandableSetupGuide(
        title = "How to connect agents via ACP (advanced)",
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded },
    ) {
        GuideText(
            "ACP (Agent Client Protocol) is a standard for direct agent-to-client communication over stdin/stdout. " +
                "It allows the dashboard to spawn agent processes and read their models, modes, and capabilities.",
        )
        GuideText("1. Click 'Add Manually' button above.")
        GuideText("2. Enter the agent's name (e.g., 'Coda').")
        GuideText("3. Enter the command path (e.g., '/usr/local/bin/letta-acp').")
        CodeExample("/usr/local/bin/letta-acp")
        GuideText("4. Enter arguments (e.g., '--yolo').")
        CodeExample("--yolo")
        GuideText("5. The dashboard will spawn the process and communicate via ACP.")
        GuideText("ACP-compatible agents: Letta Code, Claude Code, Codex CLI, GitHub Copilot, Gemini CLI.")
        GuideText(
            "Note: ACP only works on desktop (requires spawning local processes). Mobile devices get agents from the desktop via the Orchestrator provider.",
        )
    }
}

@Composable
private fun MobileSyncGuide() {
    var isExpanded by remember { mutableStateOf(false) }

    ExpandableSetupGuide(
        title = "How to see agents on mobile",
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded },
    ) {
        GuideText("Agents discovered on desktop are shared with mobile automatically:")
        GuideText("1. On your phone, go to Settings → Add Provider.")
        GuideText("2. Select 'Orchestrator' under Agent Backend.")
        GuideText("3. Enter your desktop's URL:")
        CodeExample("https://fuel.angussoftware.dev\nhttp://192.168.x.x:8321")
        GuideText("4. The phone polls the desktop every 30 seconds for agent data.")
        GuideText("5. Agents appear in the Agents tab on mobile.")
        GuideText(
            "Or: use the Sync button on your desktop's Agents tab to scan a QR code that includes the orchestrator URL.",
        )
    }
}

@Composable
private fun ExpandableSetupGuide(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
                    modifier = Modifier.graphicsLayer { rotationZ = if (isExpanded) 180f else 0f },
                )
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun GuideText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun CodeExample(code: String) {
    Text(
        text = code,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(8.dp),
    )
}

@Composable
internal fun AddAgentDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, command: String, args: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Add Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Configure an ACP-compatible agent to monitor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name (e.g., Coda, Claude)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Command path (e.g., letta-acp, claude)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Arguments (e.g., --yolo, --acp)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Common: letta-acp --yolo | claude --acp | copilot --acp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onAdd(name.trim(), command.trim(), args.trim()) },
                        enabled = name.isNotBlank() && command.isNotBlank(),
                    ) {
                        Text("Add Agent", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Agent Card
// ---------------------------------------------------------------------------

@Composable
private fun AgentCard(
    agent: AcpAgentDisplay,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onModelChange: (agentId: String, model: String) -> Unit,
    onModeChange: (agentId: String, mode: String) -> Unit,
    onRemoveAgent: (agentId: String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // --- Header row: name + lastSeen + expand arrow + status dot + delete ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // Last seen timestamp
                    agent.lastSeen?.let { seen ->
                        Text(
                            text = formatLastSeen(seen),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                StatusDot(status = agent.status)
            }

            Spacer(Modifier.height(8.dp))

            // --- Model row (always visible) ---
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

            // --- Expandable details section ---
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    agent.framework?.let { fw ->
                        DetailRow(label = "Framework", value = fw)
                    }
                    agent.command?.let { cmd ->
                        DetailRow(label = "Command", value = cmd, monospace = true)
                    }
                    agent.registeredAt?.let { ts ->
                        if (ts > 0) {
                            DetailRow(label = "Registered", value = formatRegisteredDate(ts))
                        }
                    }
                    if (agent.capabilities.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        CapabilitiesRow(capabilities = agent.capabilities)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Detail Row (for expanded section)
// ---------------------------------------------------------------------------

@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
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
                    text = currentModel ?: "\u2014",
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
                text = currentModel ?: "\u2014",
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
            style = MaterialTheme.typography.bodySmall,
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

// ---------------------------------------------------------------------------
// Timestamp formatting helpers
// ---------------------------------------------------------------------------

private fun formatLastSeen(epochMs: Long): String {
    val now = epochMillisNow()
    val diffMs = now - epochMs
    val minutes = diffMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

private fun formatRegisteredDate(epochMs: Long): String {
    val now = epochMillisNow()
    val diffMs = now - epochMs
    val days = diffMs / 86_400_000
    return when {
        days < 1 -> "today"
        days == 1L -> "yesterday"
        days < 30 -> "$days days ago"
        days < 365 -> "${days / 30} months ago"
        else -> "${days / 365} years ago"
    }
}

/** Platform-agnostic current time — uses the commonMain util epochMillis. */
private fun epochMillisNow(): Long =
    com.angussoftware.fueldashboard.util.epochMillis()
