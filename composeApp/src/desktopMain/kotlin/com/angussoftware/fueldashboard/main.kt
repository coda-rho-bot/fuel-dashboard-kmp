package com.angussoftware.fueldashboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.angussoftware.fueldashboard.model.FuelStatusModel
import com.angussoftware.fueldashboard.status.DesktopStatusSurfaces
import com.angussoftware.fueldashboard.ui.components.FuelHudContent
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
            dashboardStateProvider = { viewModel.state.value },
            onProvidersChanged = { viewModel.reloadSettings() },
            onImportSettings = { syncData -> viewModel.importSyncedSettings(syncData) },
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
                errorMessage = info.errorMessage,
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
        fun byConversation(since: Long): List<com.angussoftware.fueldashboard.presentation.ConversationUsageDisplay> {
            val titles = usageRepo.getConversationTitles()
            val rows = usageRepo.getByConversationSince(since)
            // Display-time gap fill: conversations the bulk/backfill passes haven't
            // titled yet get fetched by ID in the background — next poll shows them.
            val missing = rows.map { it.conversationId }.filter { it !in titles }.distinct()
            if (missing.isNotEmpty()) {
                serverScope.launch { usageIngestion.ensureConversationTitles(missing) }
            }
            return rows.map {
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
                    title = titles[it.conversationId],
                )
            }
        }
        fun byAgentModel(since: Long) = usageRepo.getByAgentModelSince(since).map {
            com.angussoftware.fueldashboard.presentation.AgentModelUsageDisplay(
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
            byAgentModel24h = byAgentModel(window(24)),
            byAgentModel7d = byAgentModel(window(24 * 7)),
        )
    }

    viewModel.onGetFuelHistory = {
        fuelSnapshotRepo.getRecent(120)
            .mapNotNull { it.tokensPct }
            .reversed()
    }

    // Fuel Intelligence: waste windows + merged event timeline (roadmap Phase 4)
    viewModel.onGetIntelligence = {
        val now = com.angussoftware.fueldashboard.util.epochMillis()
        val day = now - 24 * 3_600_000L
        val week = now - 7 * 24 * 3_600_000L
        val snapshots7d = fuelSnapshotRepo.getSince(week)
        val usage7d = usageRepo.getSince(week)
        // Gap reconstruction is only valid for the provider whose quota the
        // metered usage burns (z.ai via BYOK) — other providers must not be
        // reconstructed with foreign tokens.
        val zaiProviderId = viewModel.state.value.settings.providers
            .firstOrNull { it.kind == com.angussoftware.fueldashboard.model.ProviderKind.ZAI }?.id
        val providerWaste = com.angussoftware.fueldashboard.presentation.FuelIntelligence.providerWaste(
            snapshots = fuelSnapshotRepo.getProviderSnapshotsSince(week),
            usage = usage7d,
            since = week,
            now = now,
            usageOwnerProviderId = zaiProviderId,
        )
        // Fuel Advisor v3: regime from 7d snapshots, routine classification from 7d usage
        val resetAt = snapshots7d.mapNotNull { it.resetAt }.maxOrNull()
        val titles = usageRepo.getConversationTitles()
        val rawAdvice = com.angussoftware.fueldashboard.presentation.FuelAdvisor.advise(
            snapshots = snapshots7d,
            usage = usage7d,
            now = now,
            resetAt = resetAt,
        )
        val advice = when (rawAdvice) {
            is com.angussoftware.fueldashboard.presentation.FuelAdvisor.Advice.AtRisk ->
                rawAdvice.copy(routineConsumers = rawAdvice.routineConsumers.map { it.copy(title = titles[it.conversationKey]) })
            is com.angussoftware.fueldashboard.presentation.FuelAdvisor.Advice.PersistentPressure ->
                rawAdvice.copy(routineConsumers = rawAdvice.routineConsumers.map { it.copy(title = titles[it.conversationKey]) })
            else -> rawAdvice
        }
        val dropThreshold = com.angussoftware.fueldashboard.settings.loadStringSetting(
            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.EVENT_DROP_THRESHOLD, "1.0",
        ).toDoubleOrNull() ?: 1.0
        val events = com.angussoftware.fueldashboard.presentation.FuelIntelligence.fuelEvents(
            dropThresholdPct = dropThreshold,
            snapshots = fuelSnapshotRepo.getSince(week),
            modelPeriods = usageIngestionRepo.agentModelTimeline().map {
                com.angussoftware.fueldashboard.presentation.FuelIntelligence.AgentModelPeriod(
                    agentName = it.agentName,
                    model = it.model,
                    validFrom = it.validFrom,
                    validTo = it.validTo,
                )
            },
            decisions = repository.getRecent(20).map {
                com.angussoftware.fueldashboard.presentation.FuelIntelligence.DecisionRecord(
                    timestamp = it.timestamp,
                    modelHandle = it.modelHandle,
                    reason = it.reason,
                )
            },
        )
        com.angussoftware.fueldashboard.presentation.IntelligenceData(
            wasteByProvider = providerWaste,
            fuelEvents = events,
            advice = advice,
        )
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

    // Reference to the main window — the HUD's double-click brings it to front.
    val mainWindowRef = remember { java.util.concurrent.atomic.AtomicReference<java.awt.Window?>(null) }

    Window(
        onCloseRequest = {
            agentManager.stopMonitoring()
            viewModel.close()
            embeddedServer.stop()
            exitApplication()
        },
        title = "Fuel Dashboard",
        icon = appIcon(),
        state = windowState,
    ) {
        DisposableEffect(window) {
            mainWindowRef.set(window)
            onDispose { mainWindowRef.compareAndSet(window, null) }
        }
        DashboardTheme {
            FuelDashboardApp(
                viewModel = viewModel,
                themeController = themeController,
            )
        }
    }

    // ── Status HUD mini-window ────────────────────────────────────────────
    // Compact always-on-top quota/credits glance, themed like the main
    // window. Toggled from Settings → "Status HUD"; visibility observed
    // live so the toggle needs no restart. Undecorated; last position is
    // remembered between runs.
    val hudVisible = DesktopStatusSurfaces.hudVisible
    val hudState = rememberWindowState(
        width = 280.dp,
        height = 200.dp,
        position = androidx.compose.ui.window.WindowPosition(
            androidx.compose.ui.unit.Dp(
                com.angussoftware.fueldashboard.settings.loadStringSetting(
                    com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_X, "1200",
                ).toFloat(),
            ),
            androidx.compose.ui.unit.Dp(
                com.angussoftware.fueldashboard.settings.loadStringSetting(
                    com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_Y, "80",
                ).toFloat(),
            ),
        ),
    )
    if (hudVisible.value) {
        Window(
            onCloseRequest = {
                // Persist the closed state — otherwise the HUD resurrects on
                // next launch despite the user dismissing it.
                DesktopStatusSurfaces().setEnabled(false)
            },
            title = "Fuel Status",
            state = hudState,
            alwaysOnTop = DesktopStatusSurfaces.hudAlwaysOnTop.value,
            undecorated = true,
            transparent = false,
            resizable = true,
        ) {
            // Persist position between runs + enable dragging (undecorated
            // windows have no title bar to grab). Compose Desktop 1.9.0 has
            // no WindowDraggableArea, so we use AWT mouse listeners.
            DisposableEffect(window) {
                // Position persistence
                val compListener = object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(e: java.awt.event.ComponentEvent?) {
                        com.angussoftware.fueldashboard.settings.saveStringSetting(
                            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_X,
                            hudState.position.x.value.toString(),
                        )
                        com.angussoftware.fueldashboard.settings.saveStringSetting(
                            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_Y,
                            hudState.position.y.value.toString(),
                        )
                    }
                }
                window.addComponentListener(compListener)

                // Dragging: record initial click + window position on press,
                // move window on drag.
                var dragStart: java.awt.Point? = null
                var winStart: java.awt.Point? = null
                val mouseListener = object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent?) {
                        e ?: return
                        dragStart = e.locationOnScreen
                        winStart = window.location
                    }
                    override fun mouseDragged(e: java.awt.event.MouseEvent?) {
                        e ?: return
                        val ds = dragStart ?: return
                        val ws = winStart ?: return
                        val dx = e.locationOnScreen.x - ds.x
                        val dy = e.locationOnScreen.y - ds.y
                        window.location = java.awt.Point(ws.x + dx, ws.y + dy)
                    }
                    // Double-click → bring the main dashboard window to front
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        e ?: return
                        if (e.clickCount == 2) {
                            val main = mainWindowRef.get() ?: return
                            if (main is java.awt.Frame && main.state != java.awt.Frame.NORMAL) {
                                main.state = java.awt.Frame.NORMAL // un-minimize
                            }
                            main.toFront()
                            main.requestFocus()
                        }
                    }
                }
                window.addMouseListener(mouseListener)
                window.addMouseMotionListener(mouseListener)

                onDispose {
                    window.removeComponentListener(compListener)
                    window.removeMouseListener(mouseListener)
                    window.removeMouseMotionListener(mouseListener)
                }
            }
            DashboardTheme {
                val state by viewModel.state.collectAsState()
                FuelHudContent(
                    model = FuelStatusModel.from(state),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Window/taskbar icon — loaded once from bundled resources (icon.png). */
private fun appIcon(): androidx.compose.ui.graphics.painter.Painter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader
        .getResourceAsStream("icon.png")?.use { it.readBytes() }
    bytes?.let {
        androidx.compose.ui.graphics.painter.BitmapPainter(
            org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap(),        )
    }
}.getOrNull()
