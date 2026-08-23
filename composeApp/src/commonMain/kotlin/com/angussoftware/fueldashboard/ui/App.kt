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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.angussoftware.fueldashboard.network.canCheckJunieBalance
import com.angussoftware.fueldashboard.presentation.DashboardState
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.SectionOrder
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.settings.saveStringSetting
import com.angussoftware.fueldashboard.ui.components.AgentPanel
import com.angussoftware.fueldashboard.ui.components.AlertsPanel
import com.angussoftware.fueldashboard.ui.components.BudgetBar
import com.angussoftware.fueldashboard.ui.components.FuelEventHistoryPanel
import com.angussoftware.fueldashboard.ui.components.WasteDetectionPanel
import com.angussoftware.fueldashboard.ui.components.FuelBar
import com.angussoftware.fueldashboard.ui.components.formatLastUpdated
import com.angussoftware.fueldashboard.ui.components.EmptyTabState
import com.angussoftware.fueldashboard.ui.components.HelpIcon
import com.angussoftware.fueldashboard.ui.components.HelpText
import com.angussoftware.fueldashboard.ui.components.JunieProviderBalance
import com.angussoftware.fueldashboard.ui.components.FuelStatusCard
import com.angussoftware.fueldashboard.ui.components.MeteredUsagePanel
import com.angussoftware.fueldashboard.ui.components.ModelDrainRatesPanel
import com.angussoftware.fueldashboard.ui.components.RecommendationBanner
import com.angussoftware.fueldashboard.ui.components.SettingsPanel
import com.angussoftware.fueldashboard.ui.components.CountdownText
import com.angussoftware.theming.compose.ui.settings.ThemeSettingsPanel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import com.angussoftware.fueldashboard.util.formatRoot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelDashboardApp(
    viewModel: FuelViewModel,
    themeController: ThemeController = ThemeController,
) {
    val state by viewModel.state.collectAsState()

    // Tab state — hoisted above the settings overlay so it survives the toggle.
    // Without hoisting, selectedTab lives in remember{} inside DesktopLayout/MobileDashboard,
    // which resets to default when the composable leaves and re-enters composition
    // during the showSettings toggle.
    var desktopTab by remember { mutableStateOf(DesktopTab.OVERVIEW) }
    var mobileTab by remember { mutableIntStateOf(MobileTab.FUEL.ordinal) }

    // Settings overlay — when true, content area shows SettingsPanel instead of tabs
    var showSettings by remember { mutableStateOf(false) }
    // Theme popup anchored to the palette icon
    var showThemePopup by remember { mutableStateOf(false) }

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
                            if (showSettings) "Settings" else "Fuel Dashboard",
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
                        // Theme icon — quick access to theming panel
                        if (state.showThemeIcon) {
                            IconButton(onClick = { showThemePopup = true }) {
                                Icon(Icons.Default.Palette, contentDescription = "Theme")
                            }
                            DropdownMenu(
                                expanded = showThemePopup,
                                onDismissRequest = { showThemePopup = false },
                            ) {
                                ThemeSettingsPanel(themeController.settings)
                            }
                        }
                        // Settings / Close toggle
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(
                                if (showSettings) Icons.Default.Close else Icons.Default.Settings,
                                contentDescription = if (showSettings) "Close settings" else "Settings",
                            )
                        }
                    },
                )
            },
        ) { padding ->
            // Only apply top padding (for TopAppBar) — bottom is handled by mobile nav bar
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())

            if (showSettings) {
                // Settings overlay — full content area, scrollable
                Column(
                    modifier = contentModifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SettingsPanel(
                        themeController = themeController,
                        settings = state.settings,
                        viewModel = viewModel,
                        showThemeIcon = state.showThemeIcon,
                        onShowThemeIconChange = { value -> viewModel.setShowThemeIcon(value) },
                    )
                }
            } else if (isCompact) {
                MobileDashboard(
                    state = state,
                    themeController = themeController,
                    viewModel = viewModel,
                    onShowSettings = { showSettings = true },
                    modifier = contentModifier,
                    selectedTab = mobileTab,
                    onTabChange = { mobileTab = it },
                )
            } else {
                DesktopLayout(
                    state = state,
                    themeController = themeController,
                    viewModel = viewModel,
                    onShowSettings = { showSettings = true },
                    modifier = contentModifier,
                    selectedTab = desktopTab,
                    onTabChange = { desktopTab = it },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Desktop Layout — NavigationRail with unified tabs (mirrors mobile)
// ---------------------------------------------------------------------------

internal enum class DesktopTab(val label: String) {
    OVERVIEW("Overview"),
    USAGE("Usage"),
    INTEL("Intel"),
    AGENTS("Agents"),
}

@Composable
private fun DesktopLayout(
    state: DashboardState,
    themeController: ThemeController,
    viewModel: FuelViewModel,
    onShowSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    selectedTab: DesktopTab = DesktopTab.OVERVIEW,
    onTabChange: (DesktopTab) -> Unit = {},
) {

    Row(modifier = modifier.fillMaxSize()) {
        NavigationRail(
            header = {
                IconButton(onClick = { viewModel.refreshNow() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            },
        ) {
            DesktopTab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = selectedTab == tab,
                    onClick = { onTabChange(tab) },
                    icon = {
                        Icon(
                            imageVector = when (tab) {
                                DesktopTab.OVERVIEW -> Icons.Default.Dashboard
                                DesktopTab.USAGE -> Icons.Default.DataUsage
                                DesktopTab.INTEL -> Icons.Default.History
                                DesktopTab.AGENTS -> Icons.Default.Person
                            },
                            contentDescription = tab.label,
                        )
                    },
                    label = { Text(tab.label) },
                )
            }
        }

        // Content area — one tab at a time, consistent padding/spacing rhythm
        when (selectedTab) {
            DesktopTab.OVERVIEW -> {
                FuelColumnContent(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            DesktopTab.USAGE -> {
                // User-ordered sections (Settings → reorder via the arrows on each panel)
                val hasUsageData = state.meteredBySource24h.isNotEmpty() ||
                    state.meteredBySource7d.isNotEmpty() ||
                    state.meteredByConversation24h.isNotEmpty() ||
                    state.meteredByConversation7d.isNotEmpty() ||
                    state.meteredByAgentModel24h.isNotEmpty() ||
                    state.meteredByAgentModel7d.isNotEmpty() ||
                    state.modelDrainRates.isNotEmpty() ||
                    state.wasteByProvider.isNotEmpty()
                if (!hasUsageData) {
                    EmptyTabState(
                        title = "Collecting data…",
                        message = "Usage metrics appear here once the dashboard has polled your providers a few times.",
                        hint = "Add providers in Settings and wait a few minutes for the first poll cycle.",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                } else {
                    var usageOrder by remember { mutableStateOf(SectionOrder.loadUsage()) }
                    fun moveUsage(key: String, offset: Int) {
                        usageOrder = SectionOrder.move(
                            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.SECTION_ORDER_USAGE,
                            SectionOrder.USAGE_KEYS,
                            key,
                            offset,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val sections = usageOrder.mapIndexed { i, key ->
                            key to (
                                (if (i > 0) ({ moveUsage(key, -1) }) else null) to
                                    (if (i < usageOrder.size - 1) ({ moveUsage(key, +1) }) else null)
                                )
                        }.toMap()
                        for ((key, moves) in sections) {
                            val (up, down) = moves
                            when (key) {
                                "metered" -> MeteredUsagePanel(
                                    bySource24h = state.meteredBySource24h,
                                    byModel24h = state.meteredByModel24h,
                                    bySource7d = state.meteredBySource7d,
                                    byModel7d = state.meteredByModel7d,
                                    byConversation24h = state.meteredByConversation24h,
                                    byConversation7d = state.meteredByConversation7d,
                                    byAgentModel24h = state.meteredByAgentModel24h,
                                    byAgentModel7d = state.meteredByAgentModel7d,
                                    onMoveUp = up,
                                    onMoveDown = down,
                                )
                                "drain" -> if (state.modelDrainRates.isNotEmpty()) {
                                    ModelDrainRatesPanel(rates = state.modelDrainRates, onMoveUp = up, onMoveDown = down)
                                }
                                "waste" -> WasteDetectionPanel(providers = state.wasteByProvider, onMoveUp = up, onMoveDown = down)
                            }
                        }
                    }
                }
            }

            DesktopTab.INTEL -> {
                if (state.fuelEvents.isEmpty()) {
                    EmptyTabState(
                        title = "Collecting data…",
                        message = "Fuel events — gauge drops, model switches, and recommendation changes — appear here once the dashboard has been running for a few minutes.",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                } else {
                    var intelOrder by remember { mutableStateOf(SectionOrder.loadIntel()) }
                    fun moveIntel(key: String, offset: Int) {
                        intelOrder = SectionOrder.move(
                            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.SECTION_ORDER_INTEL,
                            SectionOrder.INTEL_KEYS,
                            key,
                            offset,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val sections = intelOrder.mapIndexed { i, key ->
                            key to (
                                (if (i > 0) ({ moveIntel(key, -1) }) else null) to
                                    (if (i < intelOrder.size - 1) ({ moveIntel(key, +1) }) else null)
                                )
                        }.toMap()
                        for ((key, moves) in sections) {
                            val (up, down) = moves
                            when (key) {
                                "events" -> FuelEventHistoryPanel(events = state.fuelEvents, onMoveUp = up, onMoveDown = down)
                            }
                        }
                    }
                }
            }

            DesktopTab.AGENTS -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AgentPanel(
                        agents = state.acpAgents,
                        onRemoveAgent = { agentId ->
                            viewModel.removeAgent(agentId)
                        },
                        onAddAgent = viewModel::addAgent,
                        syncData = run {
                            val key = viewModel.getServerApiKey()
                            val junie = viewModel.getJunieBalance()
                            SettingsSyncData.from(
                                settings = state.settings,
                                agentSettings = state.agentSettings,
                                themeController = themeController,
                                serverUrl = state.serverUrl,
                                serverApiKey = key.ifBlank { null },
                                junieBalance = junie,
                                junieLicense = viewModel.getJunieLicense(),
                                junieLastChecked = viewModel.getJunieLastChecked(),
                            )
                        },
                        onImportSyncedSettings = viewModel::importSyncedSettings,
                        hasConnectedOrchestrator = state.hasConnectedApi,
                        showHelp = state.showHelp,
                        usageByAgentModel24h = state.meteredByAgentModel24h,
                        usageByConversation24h = state.meteredByConversation24h,
                        onMoveAgent = { agentId, offset -> viewModel.moveAgent(agentId, offset) },
                    )
                }
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
                // Alerts at the top
                if (state.alerts.alerts.isNotEmpty()) {
                    item {
                        AlertsPanel(alerts = state.alerts.toFuelAlerts(), showHelp = state.showHelp)
                    }
                    item { HorizontalDivider() }
                }

                // Recommendation banner
                // Fuel status card — real projections from actual gauge data
                item {
                    FuelStatusCard(
                        projection = state.fuelProjection,
                        showHelp = state.showHelp,
                        fuelHistory = state.fuelHistory,
                        providerBurnRates = state.providerBurnRates,
                        modelDrainRates = state.modelDrainRates,
                        meteredByModel24h = state.meteredByModel24h,
                        meteredByConversation24h = state.meteredByConversation24h,
                        advice = state.fuelAdvice,
                    )
                }

                // Divider between dashboard overview and provider sections
                if (state.activeProviders.isNotEmpty()) {
                    item { HorizontalDivider() }
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
                        if (state.showHelp) {
                            Spacer(Modifier.width(4.dp))
                            HelpIcon("Last time provider data was polled. Updates every 30 seconds.")
                        }
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
                        isChecking = config.id in state.checkingProviderIds,
                        onCheckJunieBalance = if (canCheckJunieBalance) {
                            { viewModel.checkJunieCredits(config.id) }
                        } else {
                            null
                        },
                    )
                }

                // Usage / Intelligence / Agents live in their own tabs.
                // Settings is accessed via the app bar settings icon.
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
                text = "No providers configured. Tap the settings icon above to add a provider and start monitoring fuel levels.",
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
            "Burn rate: ${formatRoot("%.1f", burnRate)}% / hour ($dataPoints samples)"
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
    isChecking: Boolean,
    onCheckJunieBalance: (() -> Unit)?,
) {
    com.angussoftware.fueldashboard.ui.components.ProviderContent(
        config = config,
        report = report,
        error = error,
        showHelp = showHelp,
        titleStyle = MaterialTheme.typography.titleLarge,
        contentSpacing = 8.dp,
        isChecking = isChecking,
        onCheckJunieBalance = onCheckJunieBalance,
        boxedCreditBalance = true,
    )
}
