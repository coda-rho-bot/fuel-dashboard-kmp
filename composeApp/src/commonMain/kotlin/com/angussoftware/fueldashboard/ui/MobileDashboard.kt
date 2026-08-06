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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
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
import com.angussoftware.fueldashboard.presentation.DashboardState
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.components.AgentPanel
import com.angussoftware.fueldashboard.ui.components.AlertsPanel
import com.angussoftware.fueldashboard.ui.components.BudgetBar
import com.angussoftware.fueldashboard.ui.components.DecisionLog
import com.angussoftware.fueldashboard.ui.components.FuelBar
import com.angussoftware.fueldashboard.ui.components.RecommendationBanner
import com.angussoftware.fueldashboard.ui.components.SettingsPanel
import com.angussoftware.fueldashboard.ui.components.formatCountdown
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private enum class MobileTab(val label: String) {
    FUEL("Fuel"),
    AGENTS("Agents"),
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
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
            MobileEmptyState(modifier = modifier)
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

                // Burn rate status + last updated in a compact row
                item {
                    MobileStatusRow(
                        burnRate = state.burnRate,
                        dataPoints = state.dataPointCount,
                        lastUpdated = state.lastUpdated,
                        errorCount = state.providerErrors.size,
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
                    )
                }

                // Decisions (from connected API only)
                val decisions = state.decisions.decisions
                if (decisions.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        DecisionLog(decisions = decisions)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mobile Empty State
// ---------------------------------------------------------------------------

@Composable
private fun MobileEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "No providers configured",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add a provider in Settings \u2192",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
) {
    Column {
        if (burnRate == null || dataPoints < 3) {
            Text(
                text = "\u26A7 Collecting data for burn rate... ($dataPoints/3 points)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Burn rate: ${"%.1f".format(burnRate)}% / hour ($dataPoints samples)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header row: provider name + status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = config.resolvedDisplayName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
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

            Spacer(Modifier.height(12.dp))

            when (report.type) {
                ProviderType.SPEND_BUDGET -> {
                    // Budget bar (dollar-based)
                    val used = report.usedDollars
                    val limit = report.limitDollars
                    if (used != null) {
                        BudgetBar(
                            usedDollars = used,
                            limitDollars = limit,
                        )
                    } else {
                        Text(
                            "No spend data (requires admin key for costs API)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Rate-limit windows rendered as compact bars
                    val rateLimitWindows = report.windows.filter {
                        it.name == "Requests/min" || it.name == "Tokens/min"
                    }
                    if (rateLimitWindows.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        rateLimitWindows.forEach { window ->
                            MobileWindowRow(window = window)
                        }
                    }

                    // Budget-remaining window (if present)
                    val budgetWindow = report.windows.firstOrNull { it.name == "Monthly Budget" }
                    if (budgetWindow != null) {
                        Spacer(Modifier.height(8.dp))
                        MobileWindowRow(window = budgetWindow)
                    }
                }

                ProviderType.RATE_LIMIT -> {
                    report.windows.forEach { window ->
                        MobileWindowRow(window = window)
                    }
                }

                ProviderType.WINDOW_CREDIT -> {
                    if (report.windows.isNotEmpty()) {
                        report.windows.forEach { window ->
                            MobileWindowRow(window = window)
                        }
                    } else if (report.remainingPct != null) {
                        FuelBar(remainingPct = report.remainingPct, label = "Remaining")
                    } else {
                        Text(
                            "No usage data (unlimited or static)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Credit balance (Letta Cloud) — prominent display
                    if (report.creditsTotal != null && report.creditsTotal > 0) {
                        Spacer(Modifier.height(12.dp))
                        MobileCreditBalance(report = report)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mobile Window Row — compact rate-limit / window gauge
// ---------------------------------------------------------------------------

@Composable
private fun MobileWindowRow(window: ReportWindow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = window.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            window.remainingPct?.let { pct ->
                FuelBar(remainingPct = pct, compact = true, label = window.name)
            } ?: Text(
                "\u2014",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
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
// Mobile Credit Balance — styled credit info block inside the card
// ---------------------------------------------------------------------------

@Composable
private fun MobileCreditBalance(report: ProviderReport) {
    val criticallyLow = report.creditsTotal != null && report.creditsTotal < 1000
    val containerColor = if (criticallyLow)
        MaterialTheme.colorScheme.errorContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (criticallyLow)
        MaterialTheme.colorScheme.onErrorContainer
    else
        MaterialTheme.colorScheme.onSurface
    val subColor = if (criticallyLow)
        MaterialTheme.colorScheme.onErrorContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        // Balance — big and prominent
        Text(
            text = "${report.creditsTotal}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
        Text(
            text = "credits remaining",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = subColor,
        )
        // Monthly usage
        if (report.creditsUsed != null && report.creditsLimit != null) {
            Spacer(Modifier.height(6.dp))
            val overLimit = report.creditsUsed > report.creditsLimit
            Text(
                text = if (overLimit) {
                    "${report.creditsUsed} used (${report.creditsLimit} included monthly, rest pay-as-you-go)"
                } else {
                    "${report.creditsUsed} / ${report.creditsLimit} used this month"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = subColor,
            )
        }
        // Reset date
        if (report.creditsResetAt != null) {
            Text(
                text = "Quota resets ${formatCountdown(report.creditsResetAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = subColor,
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AgentPanel(
            agents = state.acpAgents,
            onModelChange = onModelChange,
            onModeChange = onModeChange,
        )

        if (state.hasConnectedApi) {
            AlertsPanel(alerts = state.alerts.toFuelAlerts())

            // Show decision history on agents tab too
            val decisions = state.decisions.decisions
            if (decisions.isNotEmpty()) {
                DecisionLog(decisions = decisions)
            }
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

// ---------------------------------------------------------------------------
// Helper — format last updated timestamp
// ---------------------------------------------------------------------------

private fun formatLastUpdated(epochMs: Long): String {
    if (epochMs == 0L) return ""
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "Updated ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}:${local.second.toString().padStart(2, '0')}"
}
