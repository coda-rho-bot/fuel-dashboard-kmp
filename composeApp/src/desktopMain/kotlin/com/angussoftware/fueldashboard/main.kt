package com.angussoftware.fueldashboard

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.angussoftware.fueldashboard.acp.AcpAgentConfig
import com.angussoftware.fueldashboard.acp.AcpAgentInfo
import com.angussoftware.fueldashboard.acp.AcpAgentManager
import com.angussoftware.fueldashboard.acp.AcpAgentStatus
import com.angussoftware.fueldashboard.database.AgentRegistry
import com.angussoftware.fueldashboard.database.DatabaseDriverFactory
import com.angussoftware.fueldashboard.database.DecisionRepository
import com.angussoftware.fueldashboard.database.FuelSnapshotRepository
import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.server.EmbeddedServer
import com.angussoftware.fueldashboard.settings.AgentSettingsStore
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.FuelDashboardApp
import com.angussoftware.fueldashboard.ui.components.AcpAgentDisplay
import com.angussoftware.fueldashboard.ui.theme.DashboardTheme
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun main() = application {
    // Configure SLF4J file logging BEFORE any SLF4J-using library initializes.
    // Routes ACP SDK, Ktor, and kotlin-logging output to a rotating log file.
    run {
        val logDir = java.io.File(System.getProperty("user.home"), ".fuel-dashboard/logs")
        logDir.mkdirs()
        val logFile = java.io.File(logDir, "fuel-dashboard.log")
        // Rotate at ~10MB: rename current to .old, start fresh
        if (logFile.exists() && logFile.length() > 10 * 1024 * 1024) {
            java.io.File(logDir, "fuel-dashboard.log.old").delete()
            logFile.renameTo(java.io.File(logDir, "fuel-dashboard.log.old"))
        }
        System.setProperty("org.slf4j.simpleLogger.logFile", logFile.absolutePath)
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
    }

    val viewModel = remember { FuelViewModel() }
    val themeController = ThemeController

    // ── Database (decision history + agent registry) ─────────────────────
    val dbDriver = remember { DatabaseDriverFactory().createDriver() }
    val repository = remember { DecisionRepository(dbDriver) }
    val fuelSnapshotRepo = remember { FuelSnapshotRepository(dbDriver) }
    val usageRepo = remember { com.angussoftware.fueldashboard.database.UsageRepository(dbDriver) }
    val usageIngestionRepo = remember { com.angussoftware.fueldashboard.database.UsageIngestionRepository(dbDriver) }
    val agentRegistry = remember { AgentRegistry(dbDriver) }

    // ── Embedded HTTP server for LAN access (mobile devices) ──────────────
    val serverScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val embeddedServer = remember {
        EmbeddedServer(
            repository = repository,
            agentRegistry = agentRegistry,
            usageRepository = usageRepo,
            onProvidersChanged = { viewModel.reloadSettings() },
        )
    }

    // Push ViewModel state → server volatile fields whenever they change
    serverScope.launch {
        viewModel.state.collect { state ->
            embeddedServer.fuelState = state.fuel
            embeddedServer.agents = state.agents.agents
            embeddedServer.alerts = state.alerts.alerts
        }
    }

    embeddedServer.start()

    // ── Usage ingestion (pluggable pull-side sources) ────────────────────
    // Connectors poll platforms that track usage server-side (e.g. Letta
    // runs) and normalize into usage_records. Disabled until configured in
    // Settings → Usage Sources.
    val usageIngestion = remember {
        com.angussoftware.fueldashboard.usage.UsageIngestionManager(
            usageRepository = usageRepo,
            ingestionRepository = usageIngestionRepo,
            httpClient = com.angussoftware.fueldashboard.network.SharedHttpClient.client,
        )
    }
    usageIngestion.start(serverScope)
    serverScope.launch {
        usageIngestion.status.collect { status ->
            viewModel.updateUsageIngestion(status)
        }
    }

    // ── Set server URL for QR sync ────────────────────────────────────────
    val serverUrl = remember {
        try {
            val conn = java.net.URL("https://fuel.angussoftware.dev").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.requestMethod = "GET"
            conn.responseCode // triggers connection
            conn.disconnect()
            "https://fuel.angussoftware.dev"
        } catch (e: Exception) {
            com.angussoftware.fueldashboard.server.getLanUrl()
        }
    }
    viewModel.setServerUrl(serverUrl)
    embeddedServer.serverUrl = serverUrl

    // ── Poll MCP/HTTP-registered agents → push to ViewModel ───────────────
    // Agents registered via MCP (POST /agents/register) or HTTP are stored
    // in EmbeddedServer.registeredAgents. Poll them every 5s and merge into
    // the ViewModel's acpAgents so they show in the UI.
    serverScope.launch {
        while (true) {
            val registered = embeddedServer.getRegisteredAgentsForDisplay()
            // Merge with ACP-discovered agents
            val acpAgents = viewModel.state.value.acpAgents
            val merged = mutableListOf<AcpAgentDisplay>()
            // Add ACP agents first
            merged.addAll(acpAgents)
            // Add registered agents that aren't already in the ACP list (avoid duplicates by id)
            for (agent in registered) {
                if (merged.none { it.id == agent.id }) {
                    merged.add(agent)
                }
            }
            if (merged != acpAgents) {
                viewModel.updateAcpAgents(merged)
            }
            kotlinx.coroutines.delay(5_000)
        }
    }

    // ── ACP Agent Manager ─────────────────────────────────────────────────
    val acpScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val agentManager = remember { AcpAgentManager() }

    // Map commonMain AgentConfig → desktop-only AcpAgentConfig
    fun toAcpConfigs(agents: List<AgentConfig>): List<AcpAgentConfig> =
        agents.map { config ->
            AcpAgentConfig(
                id = config.id,
                name = config.name,
                command = config.command,
                args = config.args.split("\\s+".toRegex()).filter { it.isNotBlank() },
                env = config.env,
            )
        }

    // Map AcpAgentInfo → AcpAgentDisplay for the ViewModel/UI layer
    fun toDisplay(infos: List<AcpAgentInfo>): List<AcpAgentDisplay> =
        infos.map { info ->
            AcpAgentDisplay(
                id = info.id,
                name = info.name,
                currentModel = info.currentModel,
                availableModels = info.availableModels,
                currentMode = info.currentMode,
                availableModes = info.availableModes,
                status = when (info.status) {
                    AcpAgentStatus.CONNECTED -> "connected"
                    AcpAgentStatus.CONNECTING -> "connecting"
                    AcpAgentStatus.ERROR -> "error"
                    AcpAgentStatus.DISCONNECTED -> "disconnected"
                },
                capabilities = info.capabilities,
                lastSeen = if (info.status == AcpAgentStatus.CONNECTED) epochMillis() else null,
            )
        }

    // Start monitoring with the initial agent settings (from disk)
    val initialAgentSettings = viewModel.state.value.agentSettings
    if (initialAgentSettings.agents.isNotEmpty()) {
        agentManager.startMonitoring(toAcpConfigs(initialAgentSettings.agents))
    } else {
        // First launch: seed default fleet agents
        val defaultConfigs = AcpAgentConfig.defaultFleet()
        if (defaultConfigs.isNotEmpty()) {
            val seededSettings = AgentSettings(
                agents = defaultConfigs.map { acp ->
                    AgentConfig(
                        id = acp.id,
                        name = acp.name,
                        command = acp.command,
                        args = acp.args.joinToString(" "),
                        env = acp.env,
                    )
                },
            )
            AgentSettingsStore.save(seededSettings)
            agentManager.startMonitoring(defaultConfigs)
        }
    }

    // Push agent manager state → ViewModel whenever it changes
    acpScope.launch {
        agentManager.agents.collect { infos ->
            viewModel.updateAcpAgents(toDisplay(infos))
        }
    }

    // Wire ViewModel callbacks → agent manager (model/mode changes)
    viewModel.onAgentModelChange = { agentId, model ->
        acpScope.launch { agentManager.setModel(agentId, model) }
    }
    viewModel.onAgentModeChange = { _, _ ->
        // Mode change not yet implemented in AcpAgentManager
    }

    // Wire ViewModel callback → EmbeddedServer (agent removal)
    viewModel.onRemoveAgent = { agentId ->
        serverScope.launch { embeddedServer.deleteRegisteredAgent(agentId) }
    }


    // Wire ViewModel callback → DecisionRepository (log decisions to SQLite)
    viewModel.onDecisionLogged = { agentId, modelHandle, provider, tier, complexity, utilizationRatio, headroom, reason ->
        serverScope.launch {
            repository.insert(agentId, modelHandle, provider, tier, complexity, utilizationRatio, headroom, reason)
        }
    }

    viewModel.onFetchDecisions = {
        repository.getRecent(10).map { record ->
            Decision(
                id = record.id,
                agentId = record.agentId,
                modelHandle = record.modelHandle,
                provider = record.provider,
                tier = record.tier,
                complexity = record.complexity,
                utilizationRatio = record.utilizationRatio,
                headroom = record.headroom.toInt(),
                reason = record.reason,
                timestamp = record.timestamp,
            )
        }
    }

    // Wire ViewModel callbacks → FuelSnapshotRepository (log real fuel data to SQLite)
    viewModel.onLogFuelSnapshot = { tokensPct, sessionPct, activeAgentCount, activeModels, resetAt ->
        serverScope.launch {
            fuelSnapshotRepo.insert(tokensPct, sessionPct, activeAgentCount, activeModels, resetAt)
            // Cleanup old snapshots weekly (keep 7 days)
            fuelSnapshotRepo.cleanup()
        }
    }

    viewModel.onComputeBurnRate = {
        fuelSnapshotRepo.computeBurnRate()
    }

    viewModel.onGetModelDrainRates = {
        fuelSnapshotRepo.getModelDrainRates().map { rate ->
            com.angussoftware.fueldashboard.presentation.ModelDrainRateDisplay(
                model = rate.model,
                totalFuelConsumed = rate.totalFuelConsumed,
                sampleCount = rate.sampleCount,
                avgDrainPerHr = rate.avgDrainPerHr,
            )
        }
    }

    viewModel.onGetMeteredUsage = {
        val now = com.angussoftware.fueldashboard.util.epochMillis()
        fun window(hours: Long) = now - hours * 3_600_000
        fun bySource(since: Long) = usageRepo.getBySourceSince(since).map {
            com.angussoftware.fueldashboard.presentation.MeteredUsageDisplay(
                label = it.source, inputTokens = it.inputTokens,
                outputTokens = it.outputTokens, requestCount = it.requestCount,
            )
        }
        fun byModel(since: Long) = usageRepo.getByModelSince(since).map {
            com.angussoftware.fueldashboard.presentation.MeteredUsageDisplay(
                label = it.model, inputTokens = it.inputTokens,
                outputTokens = it.outputTokens, requestCount = it.requestCount,
                creditCost = com.angussoftware.fueldashboard.presentation.zaiCreditCost(
                    it.model, it.inputTokens, it.outputTokens,
                ),
            )
        }
        fun byConversation(since: Long) = usageRepo.getByConversationSince(since).map {
            com.angussoftware.fueldashboard.presentation.ConversationUsageDisplay(
                conversationId = it.conversationId,
                agentName = it.source,
                model = it.model,
                inputTokens = it.inputTokens,
                outputTokens = it.outputTokens,
                requestCount = it.requestCount,
                creditCost = com.angussoftware.fueldashboard.presentation.zaiCreditCost(
                    it.model, it.inputTokens, it.outputTokens,
                ),
            )
        }
        com.angussoftware.fueldashboard.presentation.MeteredUsageWindows(
            bySource24h = bySource(window(24)),
            byModel24h = byModel(window(24)),
            bySource7d = bySource(window(24 * 7)),
            byModel7d = byModel(window(24 * 7)),
            byConversation24h = byConversation(window(24)),
            byConversation7d = byConversation(window(24 * 7)),
        )
    }

    viewModel.onGetFuelHistory = {
        fuelSnapshotRepo.getRecent(120)
            .mapNotNull { it.tokensPct }
            .reversed()
    }

    viewModel.onLogProviderSnapshots = { snapshots ->
        serverScope.launch {
            for (s in snapshots) {
                fuelSnapshotRepo.insertProviderSnapshot(
                    providerId = s.providerId,
                    providerName = s.providerName,
                    providerType = s.providerType,
                    remainingPct = s.remainingPct,
                    resetAt = s.resetAt,
                    windowHours = s.windowHours,
                )
            }
        }
    }

    viewModel.onGetProviderBurnRates = {
        fuelSnapshotRepo.getAllProviderBurnRates().map { br ->
            // Look up the original report to get quota type
            val report = viewModel.state.value.providerReports[br.providerId]
            val quotaType = report?.let { r ->
                when (r.type) {
                    com.angussoftware.fueldashboard.model.ProviderType.WINDOW_CREDIT,
                    com.angussoftware.fueldashboard.model.ProviderType.RATE_LIMIT ->
                        com.angussoftware.fueldashboard.presentation.QuotaType.RATE_WINDOW
                    com.angussoftware.fueldashboard.model.ProviderType.SPEND_BUDGET ->
                        if (r.creditsResetAt != null)
                            com.angussoftware.fueldashboard.presentation.QuotaType.CREDIT_POOL
                        else
                            com.angussoftware.fueldashboard.presentation.QuotaType.SPEND_ONLY
                }
            } ?: com.angussoftware.fueldashboard.presentation.QuotaType.RATE_WINDOW

            com.angussoftware.fueldashboard.presentation.ProviderBurnRateDisplay(
                providerId = br.providerId,
                providerName = br.providerName,
                currentPct = br.currentPct,
                burnRatePerHr = br.burnRatePerHr,
                hoursUntilReset = br.hoursUntilReset,
                hoursUntilExhaustion = br.hoursUntilExhaustion,
                projectedRemainingAtReset = br.projectedRemainingAtReset,
                willMakeIt = br.willMakeIt,
                history = br.history,
                quotaType = quotaType,
                windowHours = report?.windowHours ?: 0.0,
            )
        }
    }

    viewModel.onGetProjection = { currentPct, resetAt, burnRate ->
        fuelSnapshotRepo.projectExhaustion(currentPct, resetAt, burnRate)?.let { proj ->
            val now = com.angussoftware.fueldashboard.util.epochMillis()
            val msUntilReset = resetAt?.let { it - now } ?: 3_600_000L * 5
            val hoursUntilReset = maxOf(0.0, msUntilReset / 3_600_000.0)
            com.angussoftware.fueldashboard.presentation.FuelProjection(
                currentPct = currentPct,
                burnRatePerHr = if (burnRate > 0) burnRate else null,
                hoursUntilReset = hoursUntilReset,
                hoursUntilExhaustion = proj.hoursUntilExhaustion,
                projectedRemainingAtReset = proj.projectedRemainingAtReset,
                willMakeIt = proj.willMakeIt,
                headroomPct = proj.projectedRemainingAtReset,
                activeAgentCount = viewModel.state.value.acpAgents.count { it.status == "connected" },
                activeModels = viewModel.state.value.acpAgents.mapNotNull { it.currentModel }.distinct().sorted(),
            )
        }
    }

    // Restart agent manager when settings change (add/remove from UI)
    viewModel.onAgentSettingsChanged = { newSettings: AgentSettings ->
        agentManager.stopMonitoring()
        if (newSettings.agents.isNotEmpty()) {
            agentManager.startMonitoring(toAcpConfigs(newSettings.agents))
        } else {
            viewModel.updateAcpAgents(emptyList())
        }
    }

    val windowState = rememberWindowState(
        width = 1200.dp,
        height = 800.dp,
    )

    Window(
        onCloseRequest = {
            agentManager.stopMonitoring()
            viewModel.close()
            embeddedServer.stop()
            exitApplication()
        },
        title = "Fuel Dashboard",
        state = windowState,
    ) {
        DashboardTheme {
            FuelDashboardApp(
                viewModel = viewModel,
                themeController = themeController,
            )
        }
    }
}
