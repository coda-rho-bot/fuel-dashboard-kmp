package com.angussoftware.fueldashboard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.style.TextOverflow
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
 * Models and permissions are PER-CONVERSATION in agent runtimes. This panel
 * shows (a) the dashboard's live ACP session model for the agent, (b) what
 * models the agent's conversations actually ran in the last 24h (metered),
 * and (c) per-agent usage totals.
 *
 * @param agents       list of agents to render
 * @param onRemoveAgent invoked when the user clicks the delete button on an agent card
 * @param onAddAgent    invoked when the user submits a manual agent configuration
 * @param syncData      current settings snapshot to share with another device
 * @param onImportSyncedSettings invoked when imported settings are confirmed
 * @param hasConnectedOrchestrator whether an Orchestrator provider is configured
 * @param usageByAgentModel24h metered agent × model usage (last 24h) for honest model display
 * @param usageByConversation24h metered per-conversation usage (last 24h) for conversation counts
 * @param modifier      outer modifier
 */
@Composable
fun AgentPanel(
    agents: List<AcpAgentDisplay>,
    onRemoveAgent: (agentId: String) -> Unit,
    onAddAgent: (name: String, command: String, args: String) -> Unit,
    syncData: SettingsSyncData,
    onImportSyncedSettings: (SettingsSyncData) -> Unit,
    hasConnectedOrchestrator: Boolean,
    showHelp: Boolean = false,
    usageByAgentModel24h: List<com.angussoftware.fueldashboard.presentation.AgentModelUsageDisplay> = emptyList(),
    usageByConversation24h: List<com.angussoftware.fueldashboard.presentation.ConversationUsageDisplay> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var showQrSyncDialog by remember { mutableStateOf(false) }
    var showImportEntryDialog by remember { mutableStateOf(false) }
    var scannedSyncData by remember { mutableStateOf<SettingsSyncData?>(null) }
    val qrScanner = rememberQrScanner { scannedText ->
        if (scannedText != null) {
            SettingsSyncData.fromQrData(scannedText)?.let { parsed ->
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Agents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("AI agents connected to the dashboard. There is no single agent model or permission set — models and permissions are per conversation. Each card shows what its conversations actually ran (metered, 24h); expand a card for dashboard session controls and details.")
                }
            }
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
                HelpText("A Remote Dashboard is configured, but no agents are available. Agents appear here when they are registered on the remote dashboard.")
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
                        text = "• On mobile: connect to a remote dashboard in Settings to see desktop agents",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Usage joins: metered agent × model rows keyed by normalized agent name.
            // Metered `source` names are first names ("Coda"); ACP display names
            // may be full ("Coda, Agent Conductor") — normalize both sides.
            fun norm(s: String) = s.substringBefore(",").trim().lowercase()
            val usageByName = usageByAgentModel24h.groupBy { norm(it.agentName) }
            val convCountByAgentModel = usageByConversation24h
                .groupBy { norm(it.agentName) to it.model }
                .mapValues { (_, v) -> v.map { it.conversationId }.distinct().size }

            agents.forEach { agent ->
                val key = norm(agent.name)
                val usageRows = usageByName[key].orEmpty()
                val convCounts = convCountByAgentModel
                    .filterKeys { it.first == key }
                    .mapKeys { it.key.second }
                AgentCard(
                    agent = agent,
                    isExpanded = expandedStates[agent.id] ?: false,
                    onToggleExpand = {
                        expandedStates[agent.id] = !(expandedStates[agent.id] ?: false)
                    },
                    onRemoveAgent = onRemoveAgent,
                    showHelp = showHelp,
                    usageRows24h = usageRows,
                    conversationCountsByModel = convCounts,
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
        CodeExample("Desktop: http://localhost:8322/mcp\nLAN: http://[your-IP]:8322/mcp")
        GuideText("2. Add the MCP server to Letta Code settings.json mcpServers:")
        CodeExample(
            """
            {
              "mcpServers": {
                "fuel-dashboard": {
                  "url": "http://localhost:8322/mcp"
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
                  "url": "http://localhost:8322/mcp"
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
                "• remove_provider (remove a provider by ID or name)\n" +
                "• list_providers (see configured providers)\n" +
                "• add_orchestrator (connect to a remote dashboard)",
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
            "Note: ACP only works on desktop (requires spawning local processes). Mobile devices get agents from the desktop through a Remote Dashboard connection.",
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
        GuideText("To view agents discovered on desktop from mobile:")
        GuideText("1. On your phone, go to Settings → Add Provider.")
        GuideText("2. Select 'Remote Dashboard' under Agent Backend.")
        GuideText("3. Enter your desktop's URL:")
        CodeExample("https://fuel.angussoftware.dev\nhttp://192.168.x.x:8322")
        GuideText("4. The phone polls the desktop every 30 seconds for agent data.")
        GuideText("5. Agents appear in the Agents tab on mobile.")
        GuideText(
            "Or: use the Sync button on your desktop's Agents tab to scan a QR code that includes the remote dashboard URL.",
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
    onRemoveAgent: (agentId: String) -> Unit,
    showHelp: Boolean = false,
    usageRows24h: List<com.angussoftware.fueldashboard.presentation.AgentModelUsageDisplay> = emptyList(),
    conversationCountsByModel: Map<String, Int> = emptyMap(),
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isSyncedOnly = agent.status.equals("synced", ignoreCase = true)
    val cardAlpha = if (isSyncedOnly) 0.55f else 1f

    Card(
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = cardAlpha },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // --- Header row: name + kind badge + expand arrow + status dot + delete ---
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
                        if (isSyncedOnly) {
                            Spacer(Modifier.width(6.dp))
                            KindBadge(label = "config only")
                        }
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

                Spacer(Modifier.width(4.dp))

                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove agent",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // --- Models actually in use (metered, last 24h) — the truthful
            // --- picture: agents run different models per conversation.
            if (usageRows24h.isNotEmpty()) {
                UsageModelsRow(
                    usageRows = usageRows24h,
                    conversationCountsByModel = conversationCountsByModel,
                    showHelp = showHelp,
                )
            } else if (!isSyncedOnly) {
                Text(
                    text = "No metered usage in the last 24h — models are set per conversation; " +
                        "there's no single \"agent model\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- Expandable details section ---
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // Session model/mode controls removed — they configured the
                    // dashboard's ACP monitoring session, which has no chat/prompt
                    // surface, and the mode setter was a no-op stub. Models and
                    // permissions are per conversation in Letta; the dashboard is
                    // a monitor, not a session launcher.
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove Agent") },
            text = { Text("Remove \"${agent.name}\" from the dashboard? This stops monitoring the agent but does not delete it from Letta.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onRemoveAgent(agent.id)
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Small badge distinguishing synced/config-only entries from live agents. */
@Composable
private fun KindBadge(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The truthful model picture: which models this agent's conversations
 * actually ran in the last 24h, with tokens and conversation counts.
 */
@Composable
private fun UsageModelsRow(
    usageRows: List<com.angussoftware.fueldashboard.presentation.AgentModelUsageDisplay>,
    conversationCountsByModel: Map<String, Int>,
    showHelp: Boolean = false,
) {
    val totalTokens = usageRows.sumOf { it.inputTokens + it.outputTokens }
    val totalRequests = usageRows.sumOf { it.requestCount }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Models in use · 24h",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (showHelp) {
                Spacer(Modifier.width(4.dp))
                HelpIcon(
                    "What this agent's conversations actually ran in the last 24h, metered from usage data. " +
                        "Models and permissions are set per conversation — this is the honest picture, not a single config value.",
                )
            }
        }
        usageRows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.model,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = buildString {
                        append("${formatTokensCompact(row.inputTokens + row.outputTokens)} tokens")
                        conversationCountsByModel[row.model]?.let { append(" · $it conv") }
                        append(" · ${row.requestCount} req")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "Total 24h: ${formatTokensCompact(totalTokens)} tokens · $totalRequests requests",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
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
