package com.angussoftware.fueldashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.presentation.DashboardState
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.components.AgentFleetPanel
import com.angussoftware.fueldashboard.ui.components.AlertsPanel
import com.angussoftware.fueldashboard.ui.components.DecisionLog
import com.angussoftware.fueldashboard.ui.components.FuelBar
import com.angussoftware.fueldashboard.ui.components.RecommendationBanner
import com.angussoftware.fueldashboard.ui.components.SettingsPanel
import com.angussoftware.fueldashboard.ui.components.formatCountdown
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelDashboardApp(
    viewModel: FuelViewModel,
    themeController: ThemeController = ThemeController,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startPolling()
    }

    // --- First-run setup ---
    if (state.needsSetup) {
        SetupScreen(
            viewModel = viewModel,
            initialSettings = state.settings,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fuel Dashboard") },
                actions = {
                    IconButton(onClick = { viewModel.refreshNow() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        DashboardContent(
            state = state,
            themeController = themeController,
            viewModel = viewModel,
            modifier = Modifier.padding(padding),
        )
    }
}

// ---------------------------------------------------------------------------
// First-Run Setup Screen
// ---------------------------------------------------------------------------

@Composable
private fun SetupScreen(
    viewModel: FuelViewModel,
    initialSettings: MultiProviderSettings,
) {
    var selectedKind by remember { mutableStateOf(ProviderKind.ZAI) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Welcome to Fuel Dashboard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Add a fuel provider to get started. You can add more later.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        // Provider type selector
        Row(
            modifier = Modifier.fillMaxWidth(0.5f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderKind.entries.forEach { kind ->
                SetupKindChip(
                    label = kind.displayName,
                    isSelected = selectedKind == kind,
                    onClick = { selectedKind = kind },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // API key
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(0.5f),
            label = { Text("${selectedKind.displayName} API Key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide" else "Show")
                }
            },
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.addProvider(selectedKind, apiKey.trim())
            },
            enabled = apiKey.isNotBlank(),
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun SetupKindChip(
    label: String,
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------------
// Main Dashboard Content
// ---------------------------------------------------------------------------

@Composable
private fun DashboardContent(
    state: DashboardState,
    themeController: ThemeController,
    viewModel: FuelViewModel,
    modifier: Modifier = Modifier,
) {
    // --- Loading state ---
    if (state.isLoading && state.providerReports.isEmpty() && state.fuel == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Connecting to providers...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.activeProviders.joinToString(", ") { it.resolvedDisplayName() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // --- Error state (no data at all) ---
    val allFailed = state.providerReports.isEmpty() &&
        state.providerErrors.isNotEmpty() &&
        state.fuel == null
    if (allFailed) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Connection Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            state.providerErrors.values.firstOrNull()?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { viewModel.refreshNow() }) {
                Text("Retry")
            }
        }
        return
    }

    // --- Main content: two-column layout ---
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Left column: provider sections, recommendations, decisions
        LazyColumn(
            modifier = Modifier.weight(1.5f).fillMaxHeight(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Orchestrator fuel data (if connected)
            state.fuel?.let { fuel ->
                item {
                    RecommendationBanner(
                        recommendedModel = fuel.recommendedModel,
                        burnRate = fuel.burnRatePctPerHr,
                        surplusAlert = fuel.surplusAlert,
                    )
                }
            }

            // Burn rate status
            item {
                BurnRateStatus(
                    burnRate = state.burnRate,
                    dataPoints = state.dataPointCount,
                )
            }

            // Last updated timestamp
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatLastUpdated(state.lastUpdated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val totalErrors = state.providerErrors.size
                    if (totalErrors > 0) {
                        Text(
                            text = "\u26A0 $totalErrors error${if (totalErrors > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // Provider sections — one per active adapter
            items(state.activeProviders, key = { it.id }) { config ->
                val report = state.providerReports[config.id]
                val error = state.providerErrors[config.id]

                ProviderSection(
                    config = config,
                    report = report,
                    error = error,
                )
                HorizontalDivider()
            }

            // Orchestrator provider data (if connected)
            state.fuel?.let { fuel ->
                items(fuel.providers.entries.toList(), key = { e -> "orch-${e.key}" }) { entry ->
                    ProviderSection(
                        displayName = entry.key,
                        provider = entry.value,
                    )
                    HorizontalDivider()
                }
            }

            // Decisions (orchestrator only)
            val decisions = state.decisions.decisions
            if (decisions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    DecisionLog(decisions = decisions)
                }
            }
        }

        // Right column: settings, agents, alerts
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsPanel(
                themeController = themeController,
                settings = state.settings,
                viewModel = viewModel,
            )

            if (state.isOrchestratorConnected) {
                AgentFleetPanel(agents = state.agents.agents)
                AlertsPanel(alerts = state.alerts.toFuelAlerts())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Burn Rate Status
// ---------------------------------------------------------------------------

@Composable
private fun BurnRateStatus(
    burnRate: Double?,
    dataPoints: Int,
) {
    Column {
        if (burnRate == null || dataPoints < 3) {
            Text(
                text = "\u26A7 Collecting data for burn rate... ($dataPoints/3 points)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Burn rate: ${"%.1f".format(burnRate)}% / hour ($dataPoints samples)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Provider Section (multi-provider)
// ---------------------------------------------------------------------------

@Composable
private fun ProviderSection(
    config: ProviderConfig,
    report: ProviderReport?,
    error: String?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = config.resolvedDisplayName(),
                style = MaterialTheme.typography.titleMedium,
            )
            if (error != null) {
                Text(
                    "\u26A0 Error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (report != null && !report.available) {
                Text(
                    "UNAVAILABLE",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        if (report == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Connecting...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Spacer(Modifier.height(8.dp))

        // Overall fuel bar
        val pct = report.remainingPct
        if (pct != null) {
            FuelBar(remainingPct = pct, label = "Overall")
        } else {
            Text(
                "No usage data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Individual windows
        if (report.windows.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            report.windows.forEach { window ->
                ReportWindowRow(window = window)
            }
        }

        // Raw debug display
        if (report.rawDisplay.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = report.rawDisplay,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ReportWindowRow(window: ReportWindow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = window.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            window.remainingPct?.let { pct ->
                FuelBar(remainingPct = pct, compact = true)
            } ?: Text(
                "\u2014",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        window.resetsAt?.let { resetTime ->
            Text(
                text = formatCountdown(resetTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Legacy Provider Section (orchestrator providers — uses old model)
// ---------------------------------------------------------------------------

@Composable
private fun ProviderSection(
    displayName: String,
    provider: com.angussoftware.fueldashboard.model.Provider,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            if (!provider.available) {
                Text(
                    "UNAVAILABLE",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        val pct = provider.remainingPct
        if (pct != null) {
            FuelBar(remainingPct = pct, label = "Overall")
        } else {
            Text(
                "No usage data (unlimited or static)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (provider.windows.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            provider.windows.forEach { (windowName, window) ->
                LegacyWindowRow(windowName = windowName, window = window)
            }
        }
    }
}

@Composable
private fun LegacyWindowRow(
    windowName: String,
    window: com.angussoftware.fueldashboard.model.Window,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = windowName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            window.remainingPct?.let { pct ->
                FuelBar(remainingPct = pct, compact = true)
            } ?: Text(
                "\u2014",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        window.resetsAt?.let { resetTime ->
            Text(
                text = formatCountdown(resetTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatLastUpdated(epochMs: Long): String {
    if (epochMs == 0L) return ""
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "Updated ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}:${local.second.toString().padStart(2, '0')}"
}
