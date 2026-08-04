package com.angussoftware.fueldashboard.ui

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            onApiUrlChange = { viewModel.updateBaseUrl(it) },
            onRetry = { viewModel.refreshNow() },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun DashboardContent(
    state: DashboardState,
    themeController: ThemeController,
    onApiUrlChange: (String) -> Unit,
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
                    text = "Connecting to fuel orchestrator...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.baseUrl,
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
                "Is the fuel orchestrator running on ${state.baseUrl}?",
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
                currentApiUrl = state.baseUrl,
                onApiUrlChange = onApiUrlChange,
            )

            AgentFleetPanel(agents = state.agents.agents)

            AlertsPanel(alerts = state.alerts.alerts)
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


