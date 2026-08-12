package com.angussoftware.fueldashboard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderCategory
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.supportsMonthlyBudget
import com.angussoftware.fueldashboard.ui.components.AcpAgentDisplay
import com.angussoftware.fueldashboard.model.SettingsSyncData
import androidx.compose.runtime.collectAsState
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.ServerApiKeyStore
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.rememberQrScanner
import com.angussoftware.fueldashboard.ui.supportsQrScanning
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode

@Composable
fun SettingsPanel(
    themeController: ThemeController,
    settings: MultiProviderSettings,
    viewModel: FuelViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.state.collectAsState().value
    val serverApiKey = remember { ServerApiKeyStore.load() }
    val clipboardManager = LocalClipboardManager.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(12.dp))

            // --- Providers section ---
            ProvidersSection(
                settings = settings,
                agentSettings = state.agentSettings,
                serverUrl = state.serverUrl,
                viewModel = viewModel,
                themeController = themeController,
                showHelp = state.showHelp,
            )

            if (serverApiKey.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                ServerApiKeySection(
                    apiKey = serverApiKey,
                    onCopy = { clipboardManager.setText(AnnotatedString(serverApiKey)) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Theme mode toggle ---
            ThemeModeSection(themeController)

            Spacer(Modifier.height(12.dp))

            // --- Color theme picker ---
            ColorThemePicker(themeController)

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Help",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Show helpful explanations throughout the dashboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.showHelp,
                    onCheckedChange = viewModel::setShowHelp,
                )
            }
        }
    }
}

@Composable
private fun ServerApiKeySection(
    apiKey: String,
    onCopy: () -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }

    Text(
        text = "Embedded Server API Key",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Required for ALL requests (except GET /health). Send it as a Bearer token.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        readOnly = true,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Hide API key" else "Show API key",
                        modifier = Modifier.size(18.dp),
                    )
                }
                TextButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy API key",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Providers Section
// ---------------------------------------------------------------------------

@Composable
private fun ProvidersSection(
    settings: MultiProviderSettings,
    agentSettings: AgentSettings,
    serverUrl: String?,
    viewModel: FuelViewModel,
    themeController: ThemeController,
    showHelp: Boolean,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showQrSyncDialog by remember { mutableStateOf(false) }
    var showImportEntryDialog by remember { mutableStateOf(false) }
    var scannedSyncData by remember { mutableStateOf<SettingsSyncData?>(null) }
    var isCollapsed by remember { mutableStateOf(false) }

    // QR scanner — only functional on Android (no-op on desktop)
    val qrScanner = rememberQrScanner { scannedText ->
        if (scannedText != null) {
            SettingsSyncData.fromQrData(scannedText)?.let { parsed ->
                scannedSyncData = parsed
                showImportEntryDialog = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { isCollapsed = !isCollapsed }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = if (isCollapsed) 0f else 90f },
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Providers (${settings.providers.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showQrSyncDialog = true }) {
                Icon(Icons.Default.QrCode2, contentDescription = "Sync to mobile", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sync", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { showImportEntryDialog = true }) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Import settings", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add provider", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (showHelp) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(
                text = "• Sync: copies ALL settings (providers + agents + themes) to another device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "• Import: reads ALL settings from another device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "• Agents can also add providers automatically via MCP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ProviderMcpSetupGuide()
        }
    }

    AnimatedVisibility(
        visible = !isCollapsed,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            if (settings.providers.isEmpty()) {
                if (showHelp) {
                    HelpText("Welcome! Add a provider by clicking + Add in Providers below.")
                }
            } else {
                settings.providers.forEach { config ->
                    ProviderConfigRow(
                        config = config,
                        onUpdate = { viewModel.updateProvider(it) },
                        onRemove = { viewModel.removeProvider(config.id) },
                        showHelp = showHelp,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    // Add provider dialog
    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { kind, apiKey, name, url, monthlyBudgetUsd ->
                viewModel.addProvider(kind, apiKey, name, url, monthlyBudgetUsd)
                showAddDialog = false
            },
            showHelp = showHelp,
        )
    }

    // QR sync (generator) dialog
    if (showQrSyncDialog) {
        QrSyncDialog(
            syncData = SettingsSyncData.from(
                settings = settings,
                agentSettings = agentSettings,
                themeController = themeController,
                serverUrl = serverUrl,
                serverApiKey = ServerApiKeyStore.load().ifBlank { null },
                junieBalance = loadStringSetting(FuelSettingsKeys.JUNIE_BALANCE, "").toDoubleOrNull(),
                junieLicense = loadStringSetting(FuelSettingsKeys.JUNIE_LICENSE, "").ifBlank { null },
                junieLastChecked = loadStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, "").toLongOrNull(),
            ),
            onDismiss = { showQrSyncDialog = false },
        )
    }

    // Import entry dialog — choose between QR scan and paste code
    if (showImportEntryDialog) {
        ImportEntryDialog(
            canScanQr = supportsQrScanning,
            onScanQr = { qrScanner.launch() },
            onImportCode = { parsed -> scannedSyncData = parsed },
            onDismiss = { showImportEntryDialog = false },
        )
    }

    // Import confirmation dialog
    scannedSyncData?.let { data ->
        ImportSettingsDialog(
            syncData = data,
            onConfirm = {
                viewModel.importSyncedSettings(data)
                scannedSyncData = null
            },
            onDismiss = { scannedSyncData = null },
        )
    }
}

@Composable
private fun ProviderMcpSetupGuide() {
    var isExpanded by remember { mutableStateOf(false) }

    ProviderExpandableSetupGuide(
        title = "How agents can add providers via MCP",
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded },
    ) {
        ProviderGuideText(
            "Agents connected to the dashboard's MCP server can automatically manage LLM providers and connect a remote dashboard. " +
                "No manual entry needed.",
        )
        ProviderGuideText("MCP server URL: http://localhost:8322/mcp")
        ProviderGuideText(
            "Authentication: ALL requests (except GET /health) require the Embedded Server API Key " +
                "(shown above in Settings). Pass it as an Authorization header:\n" +
                "Authorization: Bearer <your-api-key>",
        )
        ProviderGuideText(
            "Available MCP tools for provider management:\n" +
                "• add_provider: adds an LLM provider (kind, api_key; optional name, server_url)\n" +
                "• remove_provider: removes a provider by name or ID\n" +
                "• list_providers: lists all configured providers\n" +
                "• add_orchestrator: connects to a remote dashboard (url; optional api_key)",
        )
        ProviderGuideText("Example — an agent adding a z.ai provider:")
        ProviderCodeExample(
            """
            {
              "name": "add_provider",
              "arguments": {
                "kind": "zai",
                "api_key": "your-api-key",
                "name": "z.ai (Work)"
              }
            }
            """.trimIndent(),
        )
        ProviderGuideText("Supported LLM provider kinds: zai, letta_cloud, openai, anthropic, deepseek, groq, mistral")
        ProviderGuideText(
            "When an agent adds an LLM provider or remote dashboard via MCP, it appears here automatically and starts polling for fuel data.",
        )
    }
}

@Composable
private fun ProviderExpandableSetupGuide(
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
private fun ProviderGuideText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ProviderCodeExample(code: String) {
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
private fun ProviderConfigRow(
    config: ProviderConfig,
    onUpdate: (ProviderConfig) -> Unit,
    onRemove: () -> Unit,
    showHelp: Boolean,
) {
    var isEditing by remember { mutableStateOf(false) }
    var localKey by remember(config.id, isEditing) { mutableStateOf(config.apiKey) }
    var localName by remember(config.id, isEditing) { mutableStateOf(config.displayName) }
    var localUrl by remember(config.id, isEditing) { mutableStateOf(config.serverUrl) }
    var localMonthlyBudgetUsd by remember(config.id, isEditing) {
        mutableStateOf(config.monthlyBudgetUsd.takeIf { it > 0 }?.toString().orEmpty())
    }
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (config.kind) {
                        ProviderKind.ZAI -> Icons.Default.Cloud
                        ProviderKind.LETTA_CLOUD -> Icons.Default.Cloud
                        ProviderKind.OPENAI -> Icons.Default.Api
                        ProviderKind.ANTHROPIC -> Icons.Default.Api
                        ProviderKind.DEEPSEEK -> Icons.Default.Api
                        ProviderKind.GROQ -> Icons.Default.Api
                        ProviderKind.MISTRAL -> Icons.Default.Api
                        ProviderKind.JUNIE -> Icons.Default.Api
                        ProviderKind.CONNECTED_API -> Icons.Default.Hub
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.resolvedDisplayName(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    // Show provider type in subdued text (especially useful when custom name is set)
                    if (config.displayName.isNotBlank() && config.displayName != config.kind.displayName) {
                        Text(
                            text = config.kind.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Show non-default server URL
                    val defaultUrl = config.resolvedServerUrl()
                    if (config.serverUrl.isNotBlank() && config.serverUrl != defaultUrl) {
                        Text(
                            text = config.serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (config.isConfigured) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Configured",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { isEditing = !isEditing }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (isEditing) {
                Spacer(Modifier.height(8.dp))

                // Display name
                OutlinedTextField(
                    value = localName,
                    onValueChange = { localName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display Name (optional)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))

                if (config.kind != ProviderKind.JUNIE) {
                    // API key
                    OutlinedTextField(
                        value = localKey,
                        onValueChange = { localKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (config.kind == ProviderKind.CONNECTED_API) {
                                        "Server API Key (optional)"
                                    } else {
                                        "API Key"
                                    },
                                )
                                if (showHelp) {
                                    Spacer(Modifier.width(4.dp))
                                    HelpIcon(
                                        if (config.kind == ProviderKind.CONNECTED_API) {
                                            "API key for the remote dashboard's server (required if the remote dashboard has auth enabled)"
                                        } else {
                                            "Stored locally, never shared."
                                        },
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }

                if (config.kind != ProviderKind.JUNIE) {
                    // Server URL (optional override)
                    OutlinedTextField(
                    value = localUrl,
                    onValueChange = { localUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Server URL (optional)")
                            if (showHelp) {
                                Spacer(Modifier.width(4.dp))
                                HelpIcon("Optional - only change for self-hosted endpoints.")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = {
                        Text(
                            config.resolvedServerUrl(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(
                        text = "Requires Junie CLI installed and ~/.junie/auth present. The balance checker script is bundled with the app (needs python3 + pexpect).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (config.kind.supportsMonthlyBudget) {
                    OutlinedTextField(
                        value = localMonthlyBudgetUsd,
                        onValueChange = { localMonthlyBudgetUsd = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Monthly Budget ($)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        localKey = config.apiKey
                        localName = config.displayName
                        localUrl = config.serverUrl
                        localMonthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 }?.toString().orEmpty()
                        isEditing = false
                    }) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        onUpdate(config.copy(
                            apiKey = localKey.trim(),
                            displayName = localName.trim(),
                            serverUrl = localUrl.trim(),
                            monthlyBudgetUsd = localMonthlyBudgetUsd.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0,
                        ))
                        isEditing = false
                    }) {
                        Text("Save", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else if (!config.isConfigured) {
                Text(
                    text = if (config.kind == ProviderKind.CONNECTED_API) {
                        "Server URL required"
                    } else {
                        "API key required"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (ProviderKind, String, String, String, Double) -> Unit,
    showHelp: Boolean,
) {
    var selectedKind by remember { mutableStateOf(ProviderKind.ZAI) }
    var apiKey by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var monthlyBudgetUsd by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Add Provider",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("Choose a provider. Enter its API key or the remote dashboard URL to start monitoring fuel.")
                }
            }
            Spacer(Modifier.height(12.dp))

            // Provider type dropdown
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = selectedKind.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = if (menuExpanded) 90f else 0f },
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    ProviderCategory.entries.forEach { category ->
                        // Section header
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        // Items in this category
                        ProviderKind.entries.filter { it.category == category }.forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(kind.displayName, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    selectedKind = kind
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Display name — defaults to provider name, override for multiple accounts
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name (e.g. ${selectedKind.displayName} (Work))") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = {
                    Text(selectedKind.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
            Spacer(Modifier.height(4.dp))

            // API key — required for most providers, optional for Connected API
            if (selectedKind != ProviderKind.JUNIE) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (selectedKind == ProviderKind.CONNECTED_API) {
                                    "Server API Key (optional)"
                                } else {
                                    "${selectedKind.displayName} API Key"
                                },
                            )
                            if (showHelp) {
                                Spacer(Modifier.width(4.dp))
                                HelpIcon(
                                    if (selectedKind == ProviderKind.CONNECTED_API) {
                                        "API key for the remote dashboard's server (required if the remote dashboard has auth enabled)"
                                    } else {
                                        "Stored locally, never shared."
                                    },
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
            }

            // Server URL — pre-filled with default, override for self-hosted/regional
            val defaultUrl = when (selectedKind) {
                ProviderKind.ZAI -> "https://api.z.ai"
                ProviderKind.LETTA_CLOUD -> "https://api.letta.com"
                ProviderKind.OPENAI -> "https://api.openai.com"
                ProviderKind.ANTHROPIC -> "https://api.anthropic.com"
                ProviderKind.DEEPSEEK -> "https://api.deepseek.com"
                ProviderKind.GROQ -> "https://api.groq.com/openai"
                ProviderKind.MISTRAL -> "https://api.mistral.ai"
                ProviderKind.JUNIE -> ""
                ProviderKind.CONNECTED_API -> "http://127.0.0.1:8322"
            }
            if (selectedKind != ProviderKind.JUNIE) {
                OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selectedKind == ProviderKind.CONNECTED_API) "Remote Dashboard URL" else "Server URL (override for self-hosted)")
                        if (showHelp) {
                            Spacer(Modifier.width(4.dp))
                            HelpIcon(
                                if (selectedKind == ProviderKind.CONNECTED_API) {
                                    "Connects to another Fuel Dashboard instance to monitor its providers. Enter the server API key above if the remote dashboard has auth enabled."
                                } else {
                                    "Optional - only change for self-hosted endpoints."
                                },
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = {
                    Text(defaultUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                )
            } else {
                Text(
                    text = "Requires Junie CLI installed (junie or junie-auth in PATH) and ~/.junie/auth present. The balance checker script is bundled with this app (needs python3 + pexpect). Each check costs ~$0.05-0.20.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (selectedKind.supportsMonthlyBudget) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = monthlyBudgetUsd,
                    onValueChange = { monthlyBudgetUsd = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Monthly Budget ($)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

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
                    onClick = {
                        onAdd(
                            selectedKind,
                            apiKey.trim(),
                            displayName.trim(),
                            serverUrl.trim(),
                            monthlyBudgetUsd.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0,
                        )
                    },
                    enabled = when (selectedKind) {
                        ProviderKind.CONNECTED_API -> serverUrl.isNotBlank()
                        ProviderKind.JUNIE -> true
                        else -> apiKey.isNotBlank()
                    },
                ) {
                    Text("Add Provider", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Agents Section
// ---------------------------------------------------------------------------

@Composable
private fun AgentsSection(
    agentSettings: AgentSettings,
    viewModel: FuelViewModel,
    liveAgents: List<AcpAgentDisplay>,
    showHelp: Boolean,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var isCollapsed by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { isCollapsed = !isCollapsed }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = if (isCollapsed) 0f else 90f },
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Agents (${liveAgents.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (showHelp) {
                Spacer(Modifier.width(4.dp))
                HelpIcon("Agents auto-register via MCP or can be added manually")
            }
        }
        TextButton(onClick = { showAddDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = "Add agent", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add", style = MaterialTheme.typography.labelSmall)
        }
    }

    AnimatedVisibility(
        visible = !isCollapsed,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            if (liveAgents.isEmpty() && agentSettings.agents.isEmpty()) {
                if (showHelp) {
                    HelpText("Agents can self-register via MCP (http://localhost:8322/mcp, requires server API key as Bearer token). Or add an agent manually below.")
                }
            } else {
                // Show live (MCP/HTTP-registered) agents
                liveAgents.forEach { agent ->
                    LiveAgentRow(
                        name = agent.name,
                        model = agent.currentModel,
                        status = agent.status,
                        onRemove = { viewModel.removeAgent(agent.id) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                // Show manually configured ACP agents that aren't live yet
                agentSettings.agents.forEach { agent ->
                    if (liveAgents.none { it.id == agent.id }) {
                        AgentConfigRow(
                            agent = agent,
                            onRemove = { viewModel.removeAgent(agent.id) },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAgentDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, command, args ->
                viewModel.addAgent(name, command, args)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun LiveAgentRow(
    name: String,
    model: String?,
    status: String,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(
                modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(when (status) { "connected" -> Color(0xFF4CAF50); "idle" -> Color(0xFFFFA726); else -> MaterialTheme.colorScheme.outline }),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                model?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace) }
            }
            var showConfirm by remember { mutableStateOf(false) }
            IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove agent", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            if (showConfirm) {
                AlertDialog(
                    onDismissRequest = { showConfirm = false },
                    title = { Text("Remove $name?") },
                    text = { Text("This will remove $name from the dashboard. They can re-register later.") },
                    confirmButton = {
                        TextButton(onClick = { onRemove(); showConfirm = false }) { Text("Remove") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentConfigRow(
    agent: AgentConfig,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (agent.args.isNotBlank()) {
                            "${agent.command} ${agent.args}"
                        } else {
                            agent.command
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove agent",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Theme sections (unchanged)
// ---------------------------------------------------------------------------

@Composable
private fun ThemeModeSection(themeController: ThemeController) {
    Text(
        text = "Appearance",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeModeChip(
            label = "System",
            icon = Icons.Default.SettingsApplications,
            isSelected = themeController.themeMode == ThemeMode.SYSTEM,
            onClick = { themeController.updateThemeMode(ThemeMode.SYSTEM) },
            modifier = Modifier.weight(1f),
        )
        ThemeModeChip(
            label = "Light",
            icon = Icons.Default.LightMode,
            isSelected = themeController.themeMode == ThemeMode.LIGHT,
            onClick = { themeController.updateThemeMode(ThemeMode.LIGHT) },
            modifier = Modifier.weight(1f),
        )
        ThemeModeChip(
            label = "Dark",
            icon = Icons.Default.DarkMode,
            isSelected = themeController.themeMode == ThemeMode.DARK,
            onClick = { themeController.updateThemeMode(ThemeMode.DARK) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ThemeModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------------
// Color Theme Picker
// ---------------------------------------------------------------------------

@Composable
private fun ColorThemePicker(themeController: ThemeController) {
    var isExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Palette,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Color Theme",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = activeThemeSummary(themeController),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = if (isExpanded) 90f else 0f },
        )
    }

    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        when (themeController.themeMode) {
            ThemeMode.LIGHT -> ThemeOptionList(
                label = "Light Themes",
                options = ColorTheme.lightOptions,
                selected = themeController.lightColorTheme,
                showWarningIfMismatch = false,
                isForLightMode = true,
                onSelect = { themeController.updateLightColorTheme(it) },
            )

            ThemeMode.DARK -> ThemeOptionList(
                label = "Dark Themes",
                options = ColorTheme.darkOptions,
                selected = themeController.darkColorTheme,
                showWarningIfMismatch = false,
                isForLightMode = false,
                onSelect = { themeController.updateDarkColorTheme(it) },
            )

            ThemeMode.SYSTEM -> Column {
                ThemeOptionList(
                    label = "Light Themes",
                    options = ColorTheme.allWithNames,
                    selected = themeController.lightColorTheme,
                    showWarningIfMismatch = true,
                    isForLightMode = true,
                    onSelect = { themeController.updateLightColorTheme(it) },
                )
                Spacer(Modifier.height(8.dp))
                ThemeOptionList(
                    label = "Dark Themes",
                    options = ColorTheme.allWithNames,
                    selected = themeController.darkColorTheme,
                    showWarningIfMismatch = true,
                    isForLightMode = false,
                    onSelect = { themeController.updateDarkColorTheme(it) },
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionList(
    label: String,
    options: List<Pair<ColorTheme, String>>,
    selected: ColorTheme,
    showWarningIfMismatch: Boolean,
    isForLightMode: Boolean,
    onSelect: (ColorTheme) -> Unit,
) {
    val hasMismatch = showWarningIfMismatch && selected.let {
        if (isForLightMode) !it.isLightTheme else !it.isDarkTheme
    }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (hasMismatch) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Theme mismatch warning",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            items(options) { (theme, name) ->
                ColorThemeRow(
                    name = name,
                    isSelected = theme == selected,
                    onClick = { onSelect(theme) },
                )
            }
        }
    }
}

@Composable
private fun ColorThemeRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun activeThemeSummary(controller: ThemeController): String {
    val lightName = themeDisplayName(controller.lightColorTheme)
    val darkName = themeDisplayName(controller.darkColorTheme)
    return if (controller.lightColorTheme == controller.darkColorTheme) {
        lightName
    } else {
        "$lightName / $darkName"
    }
}

private fun themeDisplayName(theme: ColorTheme): String {
    return ColorTheme.allWithNames.find { it.first == theme }?.second ?: theme.name
}
