package com.angussoftware.fueldashboard

import com.angussoftware.fueldashboard.acp.AcpAgentConfig
import com.angussoftware.fueldashboard.acp.AcpAgentInfo
import com.angussoftware.fueldashboard.acp.AcpAgentManager
import com.angussoftware.fueldashboard.acp.AcpAgentStatus
import com.angussoftware.fueldashboard.database.AgentRegistry
import com.angussoftware.fueldashboard.database.DatabaseDriverFactory
import com.angussoftware.fueldashboard.database.DecisionRepository
import com.angussoftware.fueldashboard.database.FuelSnapshotRepository
import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.presentation.FuelViewModel
import com.angussoftware.fueldashboard.server.EmbeddedServer
import com.angussoftware.fueldashboard.settings.AgentSettingsStore
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.fueldashboard.ui.components.AcpAgentDisplay
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Everything the dashboard is, minus the windows.
 *
 * The desktop app has always been the orchestrator — embedded HTTP server,
 * MCP endpoint, provider polling, SQLite history, usage ingestion, ACP agent
 * monitoring — but all of it was wired inside Compose's `application {}`
 * block, so running it meant running a GUI. On a headless box, or as a real
 * systemd service, that is the wrong shape: the orchestrator does not need a
 * display to do any of its actual work.
 *
 * This holds that wiring as a plain object graph. [start] builds it, [shutdown]
 * tears it down, and both entry points use the same one — so the GUI and the
 * `--headless` service cannot drift apart in behaviour.
 *
 * The body of [start] is the former contents of `main()`, moved rather than
 * rewritten: the only change is that each `remember { X }` became a plain `X`,
 * since there is no composition to remember across. Compose's `remember`
 * existed there purely to avoid rebuilding these objects on recomposition,
 * which a single call from a single place gives us anyway.
 */
class DashboardBackend private constructor(
    val viewModel: FuelViewModel,
    val themeController: ThemeController,
    val embeddedServer: EmbeddedServer,
    val agentManager: AcpAgentManager,
    private val serverScope: CoroutineScope,
    private val acpScope: CoroutineScope,
) {

    /**
     * Stops polling, monitoring and serving, in that order.
     *
     * Safe to call more than once: the GUI calls it from the window's close
     * handler, and the headless path from a JVM shutdown hook, and on a normal
     * GUI quit both can fire.
     */
    fun shutdown() {
        runCatching { agentManager.stopMonitoring() }
        runCatching { viewModel.close() }
        runCatching { embeddedServer.stop() }
        runCatching { acpScope.cancel() }
        runCatching { serverScope.cancel() }
    }

    companion object {

        /**
         * Routes SLF4J output (ACP SDK, Ktor, kotlin-logging) to a rotating
         * file. MUST run before any SLF4J-using library initializes, so both
         * entry points call it first.
         */
        fun configureFileLogging() {
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
        }

        /** Builds and starts the whole backend. */
        fun start(): DashboardBackend {
            val viewModel = FuelViewModel()
            val themeController = ThemeController

            // ── Database (decision history + agent registry) ─────────────────────
            val dbDriver = DatabaseDriverFactory().createDriver()
            val repository = DecisionRepository(dbDriver)
            val fuelSnapshotRepo = FuelSnapshotRepository(dbDriver)
            val usageRepo = com.angussoftware.fueldashboard.database.UsageRepository(dbDriver)
            val usageIngestionRepo = com.angussoftware.fueldashboard.database.UsageIngestionRepository(dbDriver)
            val agentRegistry = AgentRegistry(dbDriver)

            // ── Embedded HTTP server for LAN access (mobile devices) ──────────────
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val embeddedServer = EmbeddedServer(
                repository = repository,
                agentRegistry = agentRegistry,
                usageRepository = usageRepo,
                dashboardStateProvider = { viewModel.state.value },
                onProvidersChanged = { viewModel.reloadSettings() },
                onImportSettings = { syncData -> viewModel.importSyncedSettings(syncData) },
            )

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
            val usageIngestion = com.angussoftware.fueldashboard.usage.UsageIngestionManager(
                usageRepository = usageRepo,
                ingestionRepository = usageIngestionRepo,
                httpClient = com.angussoftware.fueldashboard.network.SharedHttpClient.client,
            )
            usageIngestion.start(serverScope)
            serverScope.launch {
                usageIngestion.status.collect { status ->
                    viewModel.updateUsageIngestion(status)
                }
            }

            // ── Set server URL for QR sync ────────────────────────────────────────
            // Default to the LAN URL immediately (instant — just enumerates network
            // interfaces). If the user has configured a tunnel URL in settings, probe
            // it in a background coroutine and switch to it if reachable. The probe
            // never blocks the UI thread.
            val lanUrl = com.angussoftware.fueldashboard.server.getLanUrl()
            val tunnelUrl = com.angussoftware.fueldashboard.settings.loadStringSetting(
                com.angussoftware.fueldashboard.settings.FuelSettingsKeys.TUNNEL_URL,
                "",
            )
            viewModel.setServerUrl(lanUrl)
            embeddedServer.serverUrl = lanUrl

            if (tunnelUrl.isNotBlank()) {
                serverScope.launch {
                    val reachable = try {
                        val conn = java.net.URI(tunnelUrl).toURL().openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.requestMethod = "GET"
                        conn.responseCode
                        conn.disconnect()
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (reachable) {
                        viewModel.setServerUrl(tunnelUrl)
                        embeddedServer.serverUrl = tunnelUrl
                    }
                }
            }

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
                        viewModel.updateRegisteredAgents(merged)
                    }
                    kotlinx.coroutines.delay(5_000)
                }
            }

            // ── ACP Agent Manager ─────────────────────────────────────────────────
            val acpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val agentManager = AcpAgentManager()

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
            var lastSnapshotCleanup = 0L
            viewModel.onLogFuelSnapshot = { tokensPct, sessionPct, activeAgentCount, activeModels, resetAt ->
                serverScope.launch {
                    fuelSnapshotRepo.insert(tokensPct, sessionPct, activeAgentCount, activeModels, resetAt)
                    // Cleanup runs at most once a day (this callback fires every ~30s
                    // with each poll — cleanup() on every snapshot was a hidden
                    // full-table scan 2880×/day). The sweep covers ALL retained
                    // tables: snapshots (7d), usage_records (90d — its cleanup
                    // existed but had zero callers), decisions (90d — no cleanup
                    // existed at all; architecture review H3).
                    val now = System.currentTimeMillis()
                    if (now - lastSnapshotCleanup > 24 * 3_600_000L) {
                        lastSnapshotCleanup = now
                        runCatching { fuelSnapshotRepo.cleanup() }
                        runCatching { usageRepo.cleanup() }
                        runCatching { repository.cleanup() }
                    }
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
            return DashboardBackend(
                viewModel = viewModel,
                themeController = themeController,
                embeddedServer = embeddedServer,
                agentManager = agentManager,
                serverScope = serverScope,
                acpScope = acpScope,
            )
        }
    }
}
