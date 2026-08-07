package com.angussoftware.fueldashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
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
import com.angussoftware.fueldashboard.ui.components.FuelBar
import com.angussoftware.fueldashboard.ui.components.HelpIcon
import com.angussoftware.fueldashboard.ui.components.HelpText
import com.angussoftware.fueldashboard.ui.components.RecommendationBanner
import com.angussoftware.fueldashboard.ui.components.SettingsPanel
import com.angussoftware.fueldashboard.ui.components.CountdownText
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 900.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Fuel Dashboard",
                            style = if (isCompact) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                            fontWeight = FontWeight.Bold,
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
            // Only apply top padding (for TopAppBar) — bottom is handled by mobile nav bar
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())

            if (isCompact) {
                MobileDashboard(
                    state = state,
                    themeController = themeController,
                    viewModel = viewModel,
                    modifier = contentModifier,
                )
            } else {
                DesktopLayout(
                    state = state,
                    themeController = themeController,
                    viewModel = viewModel,
                    modifier = contentModifier,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Desktop Layout — two-column (fuel left, settings/agents/alerts right)
// ---------------------------------------------------------------------------

@Composable
private fun DesktopLayout(
    state: DashboardState,
    themeController: ThemeController,
    viewModel: FuelViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        FuelColumnContent(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.weight(1.5f).fillMaxHeight(),
        )

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

            AgentPanel(
                agents = state.acpAgents,
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
                hasConnectedOrchestrator = state.hasConnectedApi,
                showHelp = state.showHelp,
            )

            if (state.hasConnectedApi) {
                AlertsPanel(alerts = state.alerts.toFuelAlerts())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Fuel Column Content — shared between desktop and mobile
// Handles empty, loading, error, and main (provider list) states.
// ---------------------------------------------------------------------------

@Composable
internal fun FuelColumnContent(
    state: DashboardState,
    viewModel: FuelViewModel,
    modifier: Modifier = Modifier,
) {
    when {
        // Empty state — no providers configured
        state.settings.providers.isEmpty() -> {
            EmptyState(showHelp = state.showHelp, modifier = modifier)
        }
        // Loading state — providers configured but no data yet
        state.isLoading && state.providerReports.isEmpty() && state.fuel == null -> {
            Box(
                modifier = modifier.fillMaxWidth(),
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
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.refreshNow() }) {
                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Main content — provider sections
        else -> {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.providerReports.isNotEmpty() || state.fuel != null) {
                    item {
                        Text(
                            text = "Providers managed in Settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Orchestrator fuel data (if connected)
                state.fuel?.let { fuel ->
                    item {
                        RecommendationBanner(
                            recommendedModel = fuel.recommendedModel,
                            burnRate = fuel.burnRatePctPerHr,
                            surplusAlert = fuel.surplusAlert,
                            showHelp = state.showHelp,
                        )
                    }
                }

                // Burn rate status
                item {
                    BurnRateStatus(
                        burnRate = state.burnRate,
                        dataPoints = state.dataPointCount,
                        showHelp = state.showHelp,
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val totalErrors = state.providerErrors.size
                        if (totalErrors > 0) {
                            Text(
                                text = "\u26A0 $totalErrors error${if (totalErrors > 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
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
                        showHelp = state.showHelp,
                    )
                    HorizontalDivider()
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
// Empty State
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(
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
                text = "No providers configured. Add your LLM provider in Settings to start monitoring fuel levels. \u2192",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showHelp) {
                Spacer(Modifier.height(16.dp))
                HelpText("Welcome! Add your LLM provider by clicking + Add in Providers below.")
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
    showHelp: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = config.resolvedDisplayName(),
                style = MaterialTheme.typography.titleLarge,
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

        Spacer(Modifier.height(8.dp))

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
                        RateLimitWindowRow(window = window, showHelp = showHelp)
                    }
                }

                // Budget-remaining window (if present)
                val budgetWindow = report.windows.firstOrNull { it.name == "Monthly Budget" }
                if (budgetWindow != null) {
                    Spacer(Modifier.height(8.dp))
                    ReportWindowRow(window = budgetWindow, showHelp = showHelp)
                }
            }

            ProviderType.RATE_LIMIT -> {
                // Rate limit only (no budget)
                report.windows.forEach { window ->
                    ReportWindowRow(window = window, showHelp = showHelp)
                }
            }

            ProviderType.WINDOW_CREDIT -> {
                // Window-credit rendering for z.ai / Letta Cloud / Connected API
                if (report.windows.isNotEmpty()) {
                    // Sub-window gauges are the primary fuel indicators
                    report.windows.forEach { window ->
                        ReportWindowRow(window = window, showHelp = showHelp)
                    }
                } else if (report.remainingPct != null) {
                    // No sub-windows — show a single overall gauge
                    FuelBar(remainingPct = report.remainingPct, label = "Remaining", showHelp = showHelp)
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
                    // Only show red if credits are critically low (under 1000), not just over monthly quota
                    val criticallyLow = report.creditsTotal < 1000
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (criticallyLow)
                                    MaterialTheme.colorScheme.errorContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            // Balance — big and prominent
                            Text(
                                text = "${report.creditsTotal}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (criticallyLow)
                                    MaterialTheme.colorScheme.onErrorContainer
                                else
                                    MaterialTheme.colorScheme.onSurface,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "credits remaining",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (criticallyLow)
                                        MaterialTheme.colorScheme.onErrorContainer
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (showHelp) {
                                    Spacer(Modifier.width(4.dp))
                                    HelpIcon("Your purchased credits.")
                                }
                            }
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Reset date
                            if (report.creditsResetAt != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Quota resets ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    CountdownText(resetsAt = report.creditsResetAt)
                                    if (showHelp) {
                                        Spacer(Modifier.width(4.dp))
                                        HelpIcon("Time until quota resets")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Raw debug display removed — not user-facing
    }
}

@Composable
private fun ReportWindowRow(window: ReportWindow, showHelp: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            window.remainingPct?.let { pct ->
                FuelBar(remainingPct = pct, compact = true, label = window.name, showHelp = showHelp)
            } ?: Text(
                "\u2014",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        window.resetsAt?.let { resetTime ->
            CountdownText(resetsAt = resetTime)
            if (showHelp) {
                Spacer(Modifier.width(4.dp))
                HelpIcon("Time until quota resets")
            }
        }
    }
}

private fun formatLastUpdated(epochMs: Long): String {
    if (epochMs == 0L) return ""
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "Updated ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}:${local.second.toString().padStart(2, '0')}"
}

/**
 * Rate-limit window row — compact bar with label, percentage, and reset countdown.
 * Uses FuelBar styling since the windows already carry remainingPct.
 */
@Composable
private fun RateLimitWindowRow(window: ReportWindow, showHelp: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            window.remainingPct?.let { pct ->
                FuelBar(remainingPct = pct, compact = true, label = window.name, showHelp = showHelp)
            } ?: Text(
                "\u2014",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        window.resetsAt?.let { resetTime ->
            CountdownText(resetsAt = resetTime)
            if (showHelp) {
                Spacer(Modifier.width(4.dp))
                HelpIcon("Time until quota resets")
            }
        }
    }
}
