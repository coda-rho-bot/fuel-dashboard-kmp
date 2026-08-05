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
import com.angussoftware.fueldashboard.model.FuelProvider
import com.angussoftware.fueldashboard.model.FuelSettings
import com.angussoftware.fueldashboard.model.FuelSourceMode
import com.angussoftware.fueldashboard.model.Provider
import com.angussoftware.fueldashboard.model.Window
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
                title = {
                    Text(
                        when (state.settings.mode) {
                            FuelSourceMode.DIRECT -> "Fuel Dashboard"
                            FuelSourceMode.CONNECTED -> "Fuel Dashboard for Letta"
                        }
                    )
                },
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
            onSettingsChange = { viewModel.updateSettings(it) },
            onRetry = { viewModel.refreshNow() },
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
    initialSettings: FuelSettings,
) {
    var mode by remember { mutableStateOf(FuelSourceMode.DIRECT) }
    var provider by remember { mutableStateOf(FuelProvider.ZAI) }
    var apiKey by remember { mutableStateOf("") }
    var orchestratorUrl by remember { mutableStateOf(initialSettings.orchestratorUrl) }
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
            text = when (mode) {
                FuelSourceMode.DIRECT -> "Enter your provider API key to get started."
                FuelSourceMode.CONNECTED -> "Enter your orchestrator URL to get started."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        // Mode toggle
        Row(
            modifier = Modifier.fillMaxWidth(0.5f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupModeChip(
                label = "Direct (Provider API)",
                isSelected = mode == FuelSourceMode.DIRECT,
                onClick = { mode = FuelSourceMode.DIRECT },
                modifier = Modifier.weight(1f),
            )
            SetupModeChip(
                label = "Connected (Orchestrator)",
                isSelected = mode == FuelSourceMode.CONNECTED,
                onClick = { mode = FuelSourceMode.CONNECTED },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(24.dp))

        when (mode) {
            FuelSourceMode.DIRECT -> {
                // Provider selector
                Row(
                    modifier = Modifier.fillMaxWidth(0.5f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FuelProvider.entries.forEach { p ->
                        SetupModeChip(
                            label = p.displayName,
                            isSelected = provider == p,
                            onClick = { provider = p },
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
                    label = { Text("${provider.displayName} API Key") },
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
                        viewModel.updateSettings(
                            initialSettings.copy(
                                mode = FuelSourceMode.DIRECT,
                                provider = provider,
                                providerApiKey = apiKey.trim(),
                            ),
                        )
                    },
                    enabled = apiKey.isNotBlank(),
                ) {
                    Text("Connect")
                }
            }

            FuelSourceMode.CONNECTED -> {
                OutlinedTextField(
                    value = orchestratorUrl,
                    onValueChange = { orchestratorUrl = it },
                    modifier = Modifier.fillMaxWidth(0.5f),
                    label = { Text("Orchestrator URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        viewModel.updateSettings(
                            initialSettings.copy(
                                mode = FuelSourceMode.CONNECTED,
                                orchestratorUrl = orchestratorUrl.trim(),
                            ),
                        )
                    },
                    enabled = orchestratorUrl.isNotBlank(),
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun SetupModeChip(
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
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
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
    onSettingsChange: (FuelSettings) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fuel = state.fuel

    // --- Loading state ---
    if (state.isLoading && fuel == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when (state.settings.mode) {
                        FuelSourceMode.DIRECT -> "Connecting to ${state.settings.provider.displayName}..."
                        FuelSourceMode.CONNECTED -> "Connecting to fuel orchestrator..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (state.settings.mode) {
                        FuelSourceMode.DIRECT -> state.settings.provider.displayName
                        FuelSourceMode.CONNECTED -> state.baseUrl
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // --- Error state (no data yet) ---
    if (state.error != null && fuel == null) {
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
            Text(
                state.error,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (state.settings.mode) {
                    FuelSourceMode.DIRECT -> "Is your ${state.settings.provider.displayName} API key valid?"
                    FuelSourceMode.CONNECTED -> "Is the fuel orchestrator running on ${state.baseUrl}?"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) {
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
        // Left column: fuel data, providers, decisions
        LazyColumn(
            modifier = Modifier.weight(1.5f).fillMaxHeight(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fuel?.let {
                item {
                    RecommendationBanner(
                        recommendedModel = it.recommendedModel,
                        burnRate = it.burnRatePctPerHr,
                        surplusAlert = it.surplusAlert,
                    )
                }

                // Burn rate status (direct mode)
                if (state.settings.mode == FuelSourceMode.DIRECT) {
                    item {
                        BurnRateStatus(
                            burnRate = state.burnRate,
                            dataPoints = state.dataPointCount,
                        )
                    }
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
                        if (state.error != null) {
                            Text(
                                text = "\u26A0 stale",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                items(it.providers.entries.toList(), key = { e -> e.key }) { entry ->
                    ProviderSection(name = entry.key, provider = entry.value)
                    HorizontalDivider()
                }
            }

            val decisions = state.decisions.decisions
            if (decisions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    DecisionLog(decisions = decisions)
                }
            }

            if (state.error != null && fuel != null) {
                item {
                    Text(
                        "\u26A0 Last refresh failed: ${state.error}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Right column: agents, alerts, settings
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
                onSettingsChange = onSettingsChange,
            )

            if (state.settings.mode == FuelSourceMode.CONNECTED) {
                AgentFleetPanel(agents = state.agents.agents)
                AlertsPanel(alerts = state.alerts.toFuelAlerts())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Burn Rate Status (direct mode only)
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

@Composable
private fun ProviderSection(
    name: String,
    provider: Provider,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
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
                WindowRow(windowName = windowName, window = window)
            }
        }
    }
}

@Composable
private fun WindowRow(
    windowName: String,
    window: Window,
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


