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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderCategory
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.supportsMonthlyBudget
import com.angussoftware.fueldashboard.model.SettingsSyncData
import androidx.compose.runtime.collectAsState
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.ServerApiKeyStore
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.status.statusSurfaces
import com.angussoftware.fueldashboard.settings.saveStringSetting
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.settings.ThemeSettingsPanel
import com.angussoftware.fueldashboard.ui.rememberQrScanner
import com.angussoftware.fueldashboard.ui.supportsQrScanning
import com.angussoftware.fueldashboard.util.isDesktopPlatform
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode

@Composable
fun SettingsPanel(
    themeController: ThemeController,
    settings: MultiProviderSettings,
    viewModel: FuelViewModel,
    showThemeIcon: Boolean = true,
    onShowThemeIconChange: (Boolean) -> Unit = {},
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
            Spacer(Modifier.height(12.dp))

            // Header row: title + global Show Help toggle (affects the whole page,
            // so it lives up top instead of buried at the bottom).
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
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Show Help",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = state.showHelp,
                    onCheckedChange = viewModel::setShowHelp,
                )
            }

            Spacer(Modifier.height(12.dp))

            // --- Providers section (core config — first) ---
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

            // --- Usage ingestion sources (pull-side metering) ---
            // Desktop-only: the ingestion manager runs in the embedded server.
            // On mobile, there's no ingestion manager, so hide this section.
            if (isDesktopPlatform) {
                UsageSourcesSection(status = state.usageIngestion)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
            }

            // --- Theme settings ---
            // When the theme icon is in the app bar (showThemeIcon=true), the full
            // theme panel is hidden here — users access it via the palette icon.
            // When the icon is hidden (showThemeIcon=false), the panel is shown inline.
            ThemeIconToggle(showThemeIcon = showThemeIcon, onShowThemeIconChange = onShowThemeIconChange)
            if (!showThemeIcon) {
                ThemeSettingsPanel(themeController.settings)
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Persistent status (Android notification / desktop HUD) ---
            StatusSurfaceSection()

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Advanced: rarely-changed config folds away by default ---
            AdvancedSection()

            Spacer(Modifier.height(16.dp))

            DocumentationFooter()
        }
    }
}

/**
 * Toggle for whether the theme icon appears in the top app bar.
 * When on, theme settings are accessed via the palette icon in the app bar.
 * When off, theme settings appear inline in the settings panel.
 */
@Composable
private fun ThemeIconToggle(
    showThemeIcon: Boolean,
    onShowThemeIconChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Show theme icon in top bar",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (showThemeIcon) {
                    "Theme settings are accessed via the palette icon in the top bar"
                } else {
                    "Theme settings appear here instead of the top bar icon"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = showThemeIcon,
            onCheckedChange = onShowThemeIconChange,
        )
    }
}

/**
 * Persistent status surface toggle — Android: ongoing notification;
 * desktop: HUD mini-window. Hidden on platforms without a surface (iOS,
 * issue #60). State applies instantly (a toggle, not a draft field).
 */
@Composable
private fun StatusSurfaceSection() {
    val surfaces = remember { statusSurfaces() }
    if (!surfaces.supported) return

    var enabled by remember { mutableStateOf(surfaces.isEnabled()) }
    var showIcon by remember { mutableStateOf(surfaces.showIcon()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = surfaces.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Quota %, time to reset, and credit totals stay visible${if (surfaces.label.contains("notification")) " in the notification bar" else " in a compact window"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                surfaces.setEnabled(it)
            },
        )
    }

    // Icon visibility toggle (Android only) — shown when notification is enabled
    if (enabled && surfaces.supportsIconToggle) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Show icon in status bar",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Hide the status bar icon while keeping the notification visible",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = showIcon,
                onCheckedChange = {
                    showIcon = it
                    surfaces.setShowIcon(it)
                },
            )
        }
    }

    // Always-on-top toggle (desktop only) — shown when HUD is enabled
    if (enabled && surfaces.supportsAlwaysOnTopToggle) {
        var onTop by remember { mutableStateOf(surfaces.alwaysOnTop()) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Always on top",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Keep the HUD floating above other windows",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = onTop,
                onCheckedChange = {
                    onTop = it
                    surfaces.setAlwaysOnTop(it)
                },
            )
        }
    }
}

/** One-line documentation link — reference material, belongs at the bottom. */
@Composable
private fun DocumentationFooter() {
    val uriHandler = LocalUriHandler.current
    Text(
        text = "Documentation: docs.angussoftware.dev/fuel-dashboard",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable {
            uriHandler.openUri("https://docs.angussoftware.dev/fuel-dashboard")
        },
    )
}

/**
 * Advanced settings — rarely-changed config folded away by default.
 * Contains the intelligence tuning knob and the issue-tracker wiring.
 * Text inputs use explicit Save (local draft state), NOT save-per-keystroke.
 */
@Composable
private fun AdvancedSection() {
    var isCollapsed by remember {
        mutableStateOf(loadStringSetting(FuelSettingsKeys.COLLAPSED_ADVANCED, "true").toBoolean())
    }

    CollapsibleSectionHeader(
        title = "Advanced",
        isCollapsed = isCollapsed,
        onToggle = {
            isCollapsed = !isCollapsed
            saveStringSetting(FuelSettingsKeys.COLLAPSED_ADVANCED, isCollapsed.toString())
        },
    )

    AnimatedVisibility(
        visible = !isCollapsed,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            IntelligenceSettings()
            Spacer(Modifier.height(16.dp))
            FeedbackSettings()
        }
    }
}

/** Shared header for collapsible sections — one treatment across the page. */
@Composable
internal fun CollapsibleSectionHeader(
    title: String,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
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
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * Fuel event drop threshold — explicit Save with validation.
 * The displayed draft can diverge from the persisted value while typing;
 * Save is the only path that writes, and invalid drafts are rejected with
 * a visible error instead of silently keeping the old value.
 */
@Composable
private fun IntelligenceSettings() {
    var draft by remember {
        mutableStateOf(loadStringSetting(FuelSettingsKeys.EVENT_DROP_THRESHOLD, "1.0"))
    }
    var error by remember { mutableStateOf<String?>(null) }

    Text(
        text = "Intelligence",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            label = { Text("Fuel event drop threshold (%)") },
            supportingText = {
                Text(error ?: "Gauge drops ≥ this become history events. Default 1. Applies on next poll.")
            },
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = {
            val v = draft.toDoubleOrNull()
            if (v == null || v !in 0.1..20.0) {
                error = "Enter a number between 0.1 and 20.0"
            } else {
                saveStringSetting(FuelSettingsKeys.EVENT_DROP_THRESHOLD, v.toString())
                error = null
            }
        }) {
            Text("Save")
        }
    }
}

/**
 * Issue-tracker wiring + report action. Config fields are drafts until
 * Save — no more write-per-keystroke into persisted settings.
 */
@Composable
private fun FeedbackSettings() {
    var showReportDialog by remember { mutableStateOf(false) }

    Text(
        text = "Feedback",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Spotted a bug or have an idea? Send it straight to the team — no account needed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = { showReportDialog = true }) {
        Text("Send feedback")
    }

    if (showReportDialog) {
        ReportIssueDialog(
            forgejoUrl = loadStringSetting(FuelSettingsKeys.FEEDBACK_URL, "https://git.angussoftware.dev"),
            repo = loadStringSetting(FuelSettingsKeys.FEEDBACK_REPO, "coda/fuel-dashboard-kmp"),
            token = loadStringSetting(FuelSettingsKeys.FEEDBACK_TOKEN, FuelSettingsKeys.DEFAULT_FEEDBACK_TOKEN),
            onDismiss = { showReportDialog = false },
        )
    }
}

@Composable
private fun ReportIssueDialog(
    forgejoUrl: String,
    repo: String,
    token: String,
    onDismiss: () -> Unit,
    /** Injectable for tests/preview — defaults to the real Forgejo submission. */
    onSubmit: suspend (title: String, body: String) -> com.angussoftware.fueldashboard.network.FeedbackSubmitter.Result =
        { t, b ->
            com.angussoftware.fueldashboard.network.FeedbackSubmitter.submit(
                forgejoUrl = forgejoUrl,
                repo = repo,
                token = token,
                title = "[app feedback] " + t,
                body = b,
            )
        },
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<com.angussoftware.fueldashboard.network.FeedbackSubmitter.Result?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Report an issue") },
        text = {
            Column {
                if (result != null) {
                    when (val r = result) {
                        is com.angussoftware.fueldashboard.network.FeedbackSubmitter.Result.Success -> {
                            Text("Issue #" + r.number + " created:", fontWeight = FontWeight.Bold)
                            Text(r.url, color = MaterialTheme.colorScheme.primary)
                        }
                        is com.angussoftware.fueldashboard.network.FeedbackSubmitter.Result.Failure -> {
                            Text("Failed: " + r.message, color = MaterialTheme.colorScheme.error)
                        }
                        null -> {}
                    }
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("What happened? (steps, expected, actual)") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            when (result) {
                is com.angussoftware.fueldashboard.network.FeedbackSubmitter.Result.Success -> {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                is com.angussoftware.fueldashboard.network.FeedbackSubmitter.Result.Failure -> {
                    TextButton(onClick = { result = null }) { Text("Try again") }
                }
                null -> {
                    TextButton(
                        onClick = {
                            if (title.isBlank()) return@TextButton
                            submitting = true
                            scope.launch {
                                result = onSubmit(title, body)
                                submitting = false
                            }
                        },
                        enabled = title.isNotBlank() && !submitting,
                    ) { Text(if (submitting) "Submitting…" else "Submit") }
                }
            }
        },
        dismissButton = {
            if (result == null) {
                TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
            }
        },
    )
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
    var isCollapsed by remember {
        mutableStateOf(loadStringSetting(FuelSettingsKeys.COLLAPSED_PROVIDERS, "false").toBoolean())
    }

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
        CollapsibleSectionHeader(
            title = "Providers (${settings.providers.size})",
            isCollapsed = isCollapsed,
            onToggle = {
                isCollapsed = !isCollapsed
                saveStringSetting(FuelSettingsKeys.COLLAPSED_PROVIDERS, isCollapsed.toString())
            },
        )
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
                text = "• Sync: copies settings (providers, connection, usage sources, themes, preferences) to another device — agents sync separately from the Agents page",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "• Import: applies whatever the scanned code carries (settings or agents, labeled on confirm)",
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
                // Unconditional empty state — guidance must not depend on the
                // Show Help toggle being on.
                HelpText("Welcome! Add a provider using the + Add button above.")
            } else {
                settings.providers.forEachIndexed { index, config ->
                    ProviderConfigRow(
                        config = config,
                        onUpdate = { viewModel.updateProvider(it) },
                        onRemove = { viewModel.removeProvider(config.id) },
                        onMoveUp = if (index > 0) ({ viewModel.moveProvider(config.id, -1) }) else null,
                        onMoveDown = if (index < settings.providers.size - 1) ({ viewModel.moveProvider(config.id, +1) }) else null,
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
            ).forSettingsQr(),
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
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
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
                // Re-order controls — move provider up/down in the user-ordered list
                if (onMoveUp != null) {
                    IconButton(onClick = onMoveUp) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Move up",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onMoveDown != null) {
                    IconButton(onClick = onMoveDown) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Move down",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                var showRemoveConfirm by remember { mutableStateOf(false) }
                IconButton(onClick = { showRemoveConfirm = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                if (showRemoveConfirm) {
                    AlertDialog(
                        onDismissRequest = { showRemoveConfirm = false },
                        title = { Text("Remove provider?") },
                        text = {
                            Text("Remove \"${config.resolvedDisplayName()}\"? Its saved API key and settings are deleted. This cannot be undone.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showRemoveConfirm = false
                                onRemove()
                            }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRemoveConfirm = false }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
                IconButton(onClick = { isEditing = !isEditing }) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

