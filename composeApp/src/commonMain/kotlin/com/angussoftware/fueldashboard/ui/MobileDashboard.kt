package com.angussoftware.fueldashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.presentation.DashboardState
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.components.AgentPanel
import com.angussoftware.fueldashboard.ui.components.AlertsPanel
import com.angussoftware.fueldashboard.ui.components.BudgetBar
import com.angussoftware.fueldashboard.ui.components.DecisionLog
import com.angussoftware.fueldashboard.ui.components.MeteredUsagePanel
import com.angussoftware.fueldashboard.ui.components.FuelBar
import com.angussoftware.fueldashboard.ui.components.ModelDrainRatesPanel
import com.angussoftware.fueldashboard.ui.components.formatLastUpdated
import com.angussoftware.fueldashboard.ui.components.HelpIcon
import com.angussoftware.fueldashboard.ui.components.HelpText
import com.angussoftware.fueldashboard.ui.components.JunieProviderBalance
import com.angussoftware.fueldashboard.ui.components.FuelStatusCard
import com.angussoftware.fueldashboard.ui.components.RecommendationBanner
import com.angussoftware.fueldashboard.ui.components.SettingsPanel
import com.angussoftware.fueldashboard.ui.components.CountdownText
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private enum class MobileTab(val label: String) {
    FUEL("Fuel"),
    AGENTS("Agents"),
    DECISIONS("Decisions"),
    SETTINGS("Settings"),
}

@Composable
fun MobileDashboard(
    state: DashboardState,
    themeController: ThemeController,
    viewModel: FuelViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(MobileTab.FUEL.ordinal) }

    Column(modifier = modifier.fillMaxSize()) {
        // Content area — fills space above the nav bar
        when (MobileTab.entries.getOrElse(selectedTab) { MobileTab.FUEL }) {
            MobileTab.FUEL -> {
                MobileFuelContent(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }

            MobileTab.AGENTS -> {
                AgentsTabContent(
                    state = state,
                    onGoToSettings = { selectedTab = MobileTab.SETTINGS.ordinal },
                    onModelChange = { agentId, model ->
                        viewModel.onAgentModelChange?.invoke(agentId, model)
                    },
                    onModeChange = { agentId, mode ->
                        viewModel.onAgentModeChange?.invoke(agentId, mode)
                    },
                    onRemoveAgent = { agentId ->
                        viewModel.onRemoveAgent?.invoke(agentId)
                    },
                    onAddAgent = viewModel::addAgent,
                    syncData = SettingsSyncData.from(
                        settings = state.settings,
                        agentSettings = state.agentSettings,
                        themeController = themeController,
                        serverUrl = state.serverUrl,
                    ),
                    onImportSyncedSettings = viewModel::importSyncedSettings,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            MobileTab.DECISIONS -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Model consumption breakdown
                    if (state.modelDrainRates.isNotEmpty()) {
                        ModelDrainRatesPanel(rates = state.modelDrainRates)
                    }

                    // Metered usage (exact token counts from usage sources)
                    MeteredUsagePanel(
                        bySource24h = state.meteredBySource24h,
                        byModel24h = state.meteredByModel24h,
                        bySource7d = state.meteredBySource7d,
                        byModel7d = state.meteredByModel7d,
                    )

                    // Decision history
                    val decisions = state.decisions.decisions
                    if (decisions.isNotEmpty()) {
                        DecisionLog(decisions = decisions, showHelp = state.showHelp)
                    } else if (state.modelDrainRates.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Collecting data...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            MobileTab.SETTINGS -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SettingsPanel(
                        themeController = themeController,
                        settings = state.settings,
                        viewModel = viewModel,
                    )
                }
            }
        }

        // Bottom navigation bar
        NavigationBar {
            MobileTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab.ordinal,
                    onClick = { selectedTab = tab.ordinal },
                    icon = {
                        Icon(
                            imageVector = when (tab) {
                                MobileTab.FUEL -> Icons.Default.LocalGasStation
                                MobileTab.AGENTS -> Icons.Default.Person
                                MobileTab.DECISIONS -> Icons.Default.History
                                MobileTab.SETTINGS -> Icons.Default.Settings
                            },
                            contentDescription = tab.label,
                        )
                    },
                    label = { Text(tab.label) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mobile Fuel Content — card-based layout for each provider
// ---------------------------------------------------------------------------

@Composable
private fun MobileFuelContent(
    state: DashboardState,
    viewModel: FuelViewModel,
    modifier: Modifier = Modifier,
) {
    when {
        // Empty state — no providers configured
        state.settings.providers.isEmpty() -> {
            MobileEmptyState(
                showHelp = state.showHelp,
                modifier = modifier,
            )
        }
        // Loading state — providers configured but no data yet
        state.isLoading && state.providerReports.isEmpty() && state.fuel == null -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Connecting to providers...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Error state — all providers failed
        state.providerReports.isEmpty() && state.providerErrors.isNotEmpty() && state.fuel == null -> {
            Column(
                modifier = modifier.padding(24.dp),
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
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.refreshNow() }) {
                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Main content — card-based provider sections
        else -> {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Alerts at the top (matching desktop layout)
                if (state.alerts.alerts.isNotEmpty()) {
                    item {
                        AlertsPanel(alerts = state.alerts.toFuelAlerts(), showHelp = state.showHelp)
                    }
                    item { HorizontalDivider() }
                }

                if (state.providerReports.isNotEmpty() || state.fuel != null) {
                    item {
                        Text(
                            text = "Providers managed in Settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Fuel status card — real projections from actual gauge data
                item {
                    FuelStatusCard(
                        projection = state.fuelProjection,
                        showHelp = state.showHelp,
                        fuelHistory = state.fuelHistory,
                        providerBurnRates = state.providerBurnRates,
                        modelDrainRates = state.modelDrainRates,
                        meteredByModel24h = state.meteredByModel24h,
                    )
                }

                // Last updated + error count
                item {
                    MobileStatusRow(
                        burnRate = state.burnRate,
                        dataPoints = state.dataPointCount,
                        lastUpdated = state.lastUpdated,
                        errorCount = state.providerErrors.size,
                        showHelp = state.showHelp,
                    )
                }

                // Provider sections — each wrapped in a Card
                items(state.activeProviders, key = { it.id }) { config ->
                    val report = state.providerReports[config.id]
                    val error = state.providerErrors[config.id]

                    MobileProviderCard(
                        config = config,
                        report = report,
                        error = error,
                        showHelp = state.showHelp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mobile Empty State
// ---------------------------------------------------------------------------

@Composable
private fun MobileEmptyState(
    showHelp: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "No providers configured. Add a provider in Settings to start monitoring fuel levels. \u2192",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showHelp) {
                Spacer(Modifier.height(16.dp))
                HelpText("Welcome! Add a provider by clicking + Add in Providers below.")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mobile Status Row — burn rate + last updated compact
// ---------------------------------------------------------------------------

@Composable
private fun MobileStatusRow(
    burnRate: Double?,
    dataPoints: Int,
    lastUpdated: Long,
    errorCount: Int,
    showHelp: Boolean,
) {
    Column {
        val text = if (burnRate == null || dataPoints < 3) {
            "\u26A7 Collecting data for burn rate... ($dataPoints/3 points)"
        } else {
            "Burn rate: ${"%.1f".format(burnRate)}% / hour ($dataPoints samples)"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showHelp) {
                Spacer(Modifier.width(4.dp))
                HelpIcon("How fast you're consuming quota (per hour)")
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatLastUpdated(lastUpdated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (errorCount > 0) {
                Text(
                    text = "\u26A0 $errorCount error${if (errorCount > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mobile Provider Card — wraps ProviderSection content in a Material 3 Card
// ---------------------------------------------------------------------------

@Composable
private fun MobileProviderCard(
    config: ProviderConfig,
    report: ProviderReport?,
    error: String?,
    showHelp: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            com.angussoftware.fueldashboard.ui.components.ProviderContent(
                config = config,
                report = report,
                error = error,
                showHelp = showHelp,
                titleStyle = MaterialTheme.typography.titleMedium,
                contentSpacing = 12.dp,
                isChecking = false,
                onCheckJunieBalance = null,
                boxedCreditBalance = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Agents Tab Content — with improved empty state
// ---------------------------------------------------------------------------

@Composable
private fun AgentsTabContent(
    state: DashboardState,
    onGoToSettings: () -> Unit,
    onModelChange: (agentId: String, model: String) -> Unit,
    onModeChange: (agentId: String, mode: String) -> Unit,
    onRemoveAgent: (agentId: String) -> Unit,
    onAddAgent: (name: String, command: String, args: String) -> Unit,
    syncData: SettingsSyncData,
    onImportSyncedSettings: (SettingsSyncData) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.acpAgents.isEmpty() && state.settings.providers.isEmpty()) {
            MobileFleetEmptyState(onGoToSettings = onGoToSettings)
        } else {
            AgentPanel(
                agents = state.acpAgents,
                onModelChange = onModelChange,
                onModeChange = onModeChange,
                onRemoveAgent = onRemoveAgent,
                onAddAgent = onAddAgent,
                syncData = syncData,
                onImportSyncedSettings = onImportSyncedSettings,
                hasConnectedOrchestrator = state.hasConnectedApi,
                showHelp = state.showHelp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Mobile Fleet Empty State — centered icon + call to action button
// ---------------------------------------------------------------------------

@Composable
private fun MobileFleetEmptyState(
    onGoToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "No agents discovered",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Connect an agent backend to see agent data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGoToSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Go to Settings")
            }
        }
    }
}
