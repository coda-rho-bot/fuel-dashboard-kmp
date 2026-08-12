package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.engine.Complexity
import com.angussoftware.fueldashboard.engine.FuelConfig
import com.angussoftware.fueldashboard.engine.FuelModel
import com.angussoftware.fueldashboard.engine.FuelProviderConfig
import com.angussoftware.fueldashboard.engine.ProviderStateInfo
import com.angussoftware.fueldashboard.engine.decideModel
import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.FuelSnapshot
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.network.AnthropicProviderAdapter
import com.angussoftware.fueldashboard.network.ConnectedApiProviderAdapter
import com.angussoftware.fueldashboard.network.DeepSeekProviderAdapter
import com.angussoftware.fueldashboard.network.GroqProviderAdapter
import com.angussoftware.fueldashboard.network.JunieProviderAdapter
import com.angussoftware.fueldashboard.network.LettaCloudProviderAdapter
import com.angussoftware.fueldashboard.network.MistralProviderAdapter
import com.angussoftware.fueldashboard.network.OpenAIProviderAdapter
import com.angussoftware.fueldashboard.network.ZaiProviderAdapter
import com.angussoftware.fueldashboard.settings.AgentSettingsStore
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import com.angussoftware.fueldashboard.settings.ServerApiKeyStore
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.settings.saveStringSetting
import com.angussoftware.fueldashboard.storage.BurnRateCalculator
import com.angussoftware.fueldashboard.storage.FuelHistoryStore
import com.angussoftware.fueldashboard.ui.components.AcpAgentDisplay
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the multi-provider dashboard.
 */
data class DashboardState(
    val settings: MultiProviderSettings = MultiProviderSettings(),
    val providerReports: Map<String, ProviderReport> = emptyMap(),
    val providerErrors: Map<String, String> = emptyMap(),
    val fuel: FuelResponse? = null,
    val decisions: DecisionsResponse = DecisionsResponse(),
    val agents: AgentsResponse = AgentsResponse(),
    val alerts: AlertsResponse = AlertsResponse(),
    val isLoading: Boolean = true,
    val lastUpdated: Long = 0L,
    val burnRate: Double? = null,
    val dataPointCount: Int = 0,
    val acpAgents: List<AcpAgentDisplay> = emptyList(),
    val agentSettings: AgentSettings = AgentSettings(),
    val serverUrl: String? = null,
    val serverApiKey: String? = null,
    val junieBalance: Double? = null,
    val junieLicense: String? = null,
    val junieLastChecked: Long? = null,
    val showHelp: Boolean = true,
    val checkingProviderIds: Set<String> = emptySet(),
) {
    /** All configured providers (have enough info to poll). */
    val activeProviders: List<ProviderConfig>
        get() = settings.providers.filter { it.isConfigured }
            .sortedByDescending { it.kind == ProviderKind.CONNECTED_API }

    /** Whether any connected API (orchestrator) is active — used for agents/alerts panel visibility. */
    val hasConnectedApi: Boolean
        get() = settings.providers.any { it.kind == ProviderKind.CONNECTED_API && it.isConfigured }
}

// TEMPORARY HEURISTIC: These model names are hardcoded and will drift as
// providers update their model lineups. This should eventually be replaced
// with dynamic model discovery from each provider's API.
/**
 * Returns default model tiers for a provider kind — used by the decision engine
 * when no orchestrator is connected. This is a best-effort heuristic mapping
 * based on known provider model lineups.
 */
private fun defaultModelsFor(kind: ProviderKind): List<FuelModel> = when (kind) {
    ProviderKind.ZAI -> listOf(
        FuelModel("glm-4.5", Complexity.TRIVIAL, bareName = "glm-4.5"),
        FuelModel("glm-4.6", Complexity.LIGHT, bareName = "glm-4.6"),
        FuelModel("glm-4.7", Complexity.MEDIUM, bareName = "glm-4.7"),
        FuelModel("glm-5", Complexity.HEAVY, bareName = "glm-5"),
        FuelModel("glm-5.1", Complexity.HEAVY, bareName = "glm-5.1"),
    )
    ProviderKind.LETTA_CLOUD -> listOf(
        FuelModel("letta-lite", Complexity.LIGHT),
        FuelModel("letta-standard", Complexity.MEDIUM),
        FuelModel("letta-pro", Complexity.HEAVY),
    )
    ProviderKind.OPENAI -> listOf(
        FuelModel("gpt-4o-mini", Complexity.LIGHT),
        FuelModel("gpt-4o", Complexity.MEDIUM),
        FuelModel("o1", Complexity.HEAVY),
    )
    ProviderKind.ANTHROPIC -> listOf(
        FuelModel("claude-3-5-haiku", Complexity.LIGHT),
        FuelModel("claude-3-5-sonnet", Complexity.MEDIUM),
        FuelModel("claude-3-opus", Complexity.HEAVY),
    )
    ProviderKind.DEEPSEEK -> listOf(
        FuelModel("deepseek-chat", Complexity.MEDIUM),
        FuelModel("deepseek-reasoner", Complexity.HEAVY),
    )
    ProviderKind.GROQ -> listOf(
        FuelModel("llama-3.1-8b", Complexity.LIGHT),
        FuelModel("llama-3.1-70b", Complexity.MEDIUM),
    )
    ProviderKind.MISTRAL -> listOf(
        FuelModel("mistral-small", Complexity.LIGHT),
        FuelModel("mistral-large", Complexity.MEDIUM),
    )
    ProviderKind.JUNIE -> emptyList()
    ProviderKind.CONNECTED_API -> emptyList()
}

class FuelViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val adapters = mutableMapOf<String, ProviderAdapter>()

    private val _state = MutableStateFlow(
        DashboardState(
            isLoading = false,
            showHelp = loadStringSetting(FuelSettingsKeys.SHOW_HELP, "true").toBoolean(),
        ),
    )
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        val settings = FuelSettingsStore.loadMultiProvider()
        val agents = AgentSettingsStore.load()
        val serverKey = ServerApiKeyStore.load()
        _state.value = _state.value.copy(
            settings = settings,
            agentSettings = agents,
            serverApiKey = serverKey.ifBlank { null },
            junieBalance = loadStringSetting(FuelSettingsKeys.JUNIE_BALANCE, "").toDoubleOrNull(),
            junieLicense = loadStringSetting(FuelSettingsKeys.JUNIE_LICENSE, "").ifBlank { null },
            junieLastChecked = loadStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, "").toLongOrNull(),
        )
        if (settings.hasAnyConfig) {
            activateAdapters(settings)
        }
    }

    fun getServerApiKey(): String = ServerApiKeyStore.load()
    fun getJunieBalance(): Double? = loadStringSetting(FuelSettingsKeys.JUNIE_BALANCE, "").toDoubleOrNull()
    fun getJunieLicense(): String? = loadStringSetting(FuelSettingsKeys.JUNIE_LICENSE, "").ifBlank { null }
    fun getJunieLastChecked(): Long? = loadStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, "").toLongOrNull()

    fun startPolling() {
        if (pollJob?.isActive == true) return
        if (!_state.value.settings.hasAnyConfig) return

        pollJob = scope.launch {
            refresh()
            val interval = pollIntervalMs()
            delay(interval)
            while (true) {
                refresh()
                delay(interval)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshNow() {
        scope.launch { refresh() }
    }

    // --- ACP agent state (set from desktop main.kt) ---

    /**
     * Callbacks for ACP agent model/mode changes. Set from main.kt where the
     * AcpAgentManager lives. Null on platforms without ACP support (Android).
     */
    var onAgentModelChange: ((agentId: String, model: String) -> Unit)? = null
    var onAgentModeChange: ((agentId: String, mode: String) -> Unit)? = null
    /**
     * Callback invoked when the user clicks delete on an agent card.
     * Set from main.kt (desktop) to remove from EmbeddedServer registry.
     * Null on platforms without the embedded server (Android).
     */
    var onRemoveAgent: ((agentId: String) -> Unit)? = null


    /**
     * Callback invoked when agent settings change (add/remove).
     * Set from main.kt (desktop) to restart AcpAgentManager with new config.
     * Null on platforms without ACP support (Android).
     */
    var onAgentSettingsChanged: ((AgentSettings) -> Unit)? = null

    /**
     * Callback invoked when the decision engine picks a model.
     * Set from main.kt (desktop) to persist to SQLite via DecisionRepository.
     * Null on platforms without DB support.
     */
    var onDecisionLogged: ((
        agentId: String,
        modelHandle: String,
        provider: String,
        tier: String,
        complexity: String,
        utilizationRatio: Double,
        headroom: Int,
        reason: String,
    ) -> Unit)? = null

    /**
     * Callback to fetch recent decisions from local storage (desktop only).
     * Called after each poll to populate the DecisionLog.
     */
    var onFetchDecisions: (() -> List<Decision>)? = null

    /**
     * Push ACP agent display data into dashboard state. Called from main.kt
     * when the AcpAgentManager StateFlow emits updates.
     */
    fun updateAcpAgents(agents: List<AcpAgentDisplay>) {
        _state.value = _state.value.copy(acpAgents = agents)
    }

    fun setServerUrl(url: String?) {
        _state.value = _state.value.copy(serverUrl = url)
    }

    fun setShowHelp(showHelp: Boolean) {
        saveStringSetting(FuelSettingsKeys.SHOW_HELP, showHelp.toString())
        _state.value = _state.value.copy(showHelp = showHelp)
    }

    /** Runs Junie's chargeable balance command only after an explicit desktop user action. */
    fun checkJunieCredits(providerId: String) {
        val adapter = adapters[providerId] as? JunieProviderAdapter ?: return
        if (providerId in _state.value.checkingProviderIds) return

        _state.value = _state.value.copy(
            checkingProviderIds = _state.value.checkingProviderIds + providerId,
        )
        scope.launch {
            runCatching { adapter.checkBalance() }
                .onSuccess { report ->
                    _state.value = _state.value.copy(
                        providerReports = _state.value.providerReports + (providerId to report),
                        providerErrors = _state.value.providerErrors - providerId,
                        checkingProviderIds = _state.value.checkingProviderIds - providerId,
                        lastUpdated = epochMillis(),
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        providerErrors = _state.value.providerErrors + (providerId to (error.message ?: "Junie balance check failed")),
                        checkingProviderIds = _state.value.checkingProviderIds - providerId,
                    )
                }
        }
    }

    // --- Settings updates ---

    /**
     * Updates multi-provider settings and restarts polling with the new adapters.
     */
    fun updateSettings(newSettings: MultiProviderSettings) {
        FuelSettingsStore.saveMultiProvider(newSettings)

        applySettings(newSettings)
    }

    /**
     * Reloads provider settings persisted by an external integration such as MCP.
     */
    fun reloadSettings() {
        applySettings(FuelSettingsStore.loadMultiProvider())
    }

    private fun applySettings(newSettings: MultiProviderSettings) {
        stopPolling()
        closeAdapters()

        _state.value = _state.value.copy(
            settings = newSettings,
            isLoading = true,
            providerReports = emptyMap(),
            providerErrors = emptyMap(),
            fuel = null,
            decisions = DecisionsResponse(),
            agents = AgentsResponse(),
            alerts = AlertsResponse(),
            burnRate = null,
            dataPointCount = 0,
            checkingProviderIds = emptySet(),
        )

        if (newSettings.hasAnyConfig) {
            activateAdapters(newSettings)
            startPolling()
        }
    }

    /**
     * Adds a new provider to settings.
     */
    fun addProvider(
        kind: ProviderKind,
        apiKey: String,
        displayName: String = "",
        serverUrl: String = "",
        monthlyBudgetUsd: Double = 0.0,
    ) {
        val current = _state.value.settings
        val newProvider = ProviderConfig(
            id = FuelSettingsStore.generateProviderId(),
            kind = kind,
            apiKey = apiKey,
            displayName = displayName,
            serverUrl = serverUrl,
            monthlyBudgetUsd = monthlyBudgetUsd,
        )
        updateSettings(current.copy(providers = current.providers + newProvider))
    }

    /**
     * Removes a provider from settings.
     */
    fun removeProvider(providerId: String) {
        val current = _state.value.settings
        updateSettings(current.copy(providers = current.providers.filterNot { it.id == providerId }))
    }

    /**
     * Updates a single provider's configuration.
     */
    fun updateProvider(updated: ProviderConfig) {
        val current = _state.value.settings
        updateSettings(
            current.copy(
                providers = current.providers.map { if (it.id == updated.id) updated else it },
            ),
        )
    }

    // --- Agent settings ---

    /**
     * Adds a new ACP agent to settings.
     * Persists immediately and notifies the callback (main.kt restarts the manager).
     */
    fun addAgent(name: String, command: String, args: String) {
        val newConfig = AgentConfig(
            id = AgentSettingsStore.generateAgentId(),
            name = name,
            command = command,
            args = args,
        )
        val updated = _state.value.agentSettings.copy(
            agents = _state.value.agentSettings.agents + newConfig,
        )
        AgentSettingsStore.save(updated)
        _state.value = _state.value.copy(agentSettings = updated)
        onAgentSettingsChanged?.invoke(updated)
    }

    /**
     * Removes an ACP agent from settings by ID.
     */
    fun removeAgent(agentId: String) {
        // Remove from ACP agent settings
        val updated = _state.value.agentSettings.copy(
            agents = _state.value.agentSettings.agents.filterNot { it.id == agentId },
        )
        AgentSettingsStore.save(updated)
        _state.value = _state.value.copy(agentSettings = updated)
        onAgentSettingsChanged?.invoke(updated)
        // Also remove from MCP/HTTP registered agents
        onRemoveAgent?.invoke(agentId)
        // Remove from the displayed agent list
        _state.value = _state.value.copy(
            acpAgents = _state.value.acpAgents.filterNot { it.id == agentId },
        )
    }

    /**
     * Imports synced settings from a QR code scan.
     *
     * Replaces all current providers, agent configurations, and theme with the imported data.
     */
    fun importSyncedSettings(syncData: com.angussoftware.fueldashboard.model.SettingsSyncData) {
        // Merge: take synced providers AND add/update a Remote Dashboard provider with the server API key
        val providers = syncData.providers.toMutableList()
        syncData.serverUrl?.let { url ->
            val key = syncData.serverApiKey.orEmpty()
            // Remove any existing CONNECTED_API and add fresh one
            providers.removeAll { it.kind == com.angussoftware.fueldashboard.model.ProviderKind.CONNECTED_API }
            providers.add(
                com.angussoftware.fueldashboard.model.ProviderConfig(
                    id = "synced-orchestrator",
                    kind = com.angussoftware.fueldashboard.model.ProviderKind.CONNECTED_API,
                    apiKey = key,
                    displayName = "Remote Dashboard",
                    serverUrl = url,
                ),
            )
        }
        updateSettings(com.angussoftware.fueldashboard.model.MultiProviderSettings(providers = providers))

        AgentSettingsStore.save(syncData.agentSettings)
        _state.value = _state.value.copy(agentSettings = syncData.agentSettings)
        onAgentSettingsChanged?.invoke(syncData.agentSettings)

        // Populate agent display list from synced configs (for mobile — no live ACP available)
        if (_state.value.acpAgents.isEmpty() && syncData.agentSettings.agents.isNotEmpty()) {
            _state.value = _state.value.copy(
                acpAgents = syncData.agentSettings.agents.map { config ->
                    AcpAgentDisplay(
                        id = config.id,
                        name = config.name,
                        currentModel = "unknown",
                        availableModels = emptyList(),
                        currentMode = null,
                        availableModes = emptyList(),
                        status = "synced",
                        capabilities = emptyList(),
                        lastSeen = null,
                    )
                },
            )
        }

        // Apply theme settings
        val themeController = com.angussoftware.fueldashboard.settings.ThemeController

        // Apply Junie balance data (synced from desktop)
        syncData.junieBalance?.let { balance ->
            saveStringSetting(FuelSettingsKeys.JUNIE_BALANCE, balance.toString())
        }
        syncData.junieLicense?.let { license ->
            saveStringSetting(FuelSettingsKeys.JUNIE_LICENSE, license)
        }
        syncData.junieLastChecked?.let { checked ->
            saveStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, checked.toString())
        }

        runCatching {
            val mode = com.angussoftware.theming.compose.ui.theme.ThemeMode.valueOf(syncData.themeMode)
            themeController.updateThemeMode(mode)
        }
        runCatching {
            val light = com.angussoftware.theming.compose.ui.theme.ColorTheme.valueOf(syncData.lightColorTheme)
            themeController.updateLightColorTheme(light)
        }
        runCatching {
            val dark = com.angussoftware.theming.compose.ui.theme.ColorTheme.valueOf(syncData.darkColorTheme)
            themeController.updateDarkColorTheme(dark)
        }

        // Remote Dashboard provider is already added above in the providers list
    }

    // --- Internals ---

    private fun activateAdapters(settings: MultiProviderSettings) {
        for (config in settings.providers) {
            if (!config.isConfigured) continue
            val adapter = createAdapter(config) ?: continue
            adapters[config.id] = adapter
        }
    }

    private fun createAdapter(config: ProviderConfig): ProviderAdapter? {
        return when (config.kind) {
            ProviderKind.ZAI -> ZaiProviderAdapter(
                providerId = config.id,
                apiKey = config.apiKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.LETTA_CLOUD -> LettaCloudProviderAdapter(
                providerId = config.id,
                apiKey = config.apiKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.OPENAI -> OpenAIProviderAdapter(
                providerId = config.id,
                apiKey = config.apiKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.ANTHROPIC -> AnthropicProviderAdapter(
                providerId = config.id,
                apiKey = config.apiKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.DEEPSEEK -> DeepSeekProviderAdapter(
                providerId = config.id,
                apiKey = config.apiKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.GROQ -> GroqProviderAdapter(
                providerId = config.id,
                apiKey = config.apiKey,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.MISTRAL -> MistralProviderAdapter(
                providerId = config.id,
                apiKey = config.apiKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = config.monthlyBudgetUsd.takeIf { it > 0 },
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.JUNIE -> JunieProviderAdapter(
                providerId = config.id,
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.CONNECTED_API -> ConnectedApiProviderAdapter(
                providerId = config.id,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
                apiKey = config.apiKey,
            )
        }
    }

    private fun closeAdapters() {
        adapters.values.forEach { runCatching { it.close() } }
        adapters.clear()
    }

    /**
     * Poll interval: 30s for all providers.
     * Connected API was previously 30s, direct providers 5min.
     * Unified to 30s for responsive UX — provider APIs can handle it.
     */
    private fun pollIntervalMs(): Long = 30_000L

    private suspend fun refresh() {
        if (adapters.isEmpty()) {
            _state.value = _state.value.copy(
                isLoading = false,
                providerErrors = mapOf("global" to "No providers configured"),
            )
            return
        }

        // Poll all provider adapters in parallel
        val reportResults = adapters.values.map { adapter ->
            scope.async {
                val providerId = adapter.providerId
                try {
                    val report = adapter.poll()
                    providerId to Result.success(report)
                } catch (e: Exception) {
                    providerId to Result.failure(e)
                }
            }
        }.awaitAll()

        val reports = mutableMapOf<String, ProviderReport>()
        val errors = mutableMapOf<String, String>()

        for ((providerId, result) in reportResults) {
            result
                .onSuccess { reports[providerId] = it }
                .onFailure { errors[providerId] = it.message ?: "Unknown error" }
        }

        // Extract orchestrator data from any connected API adapter
        var fuel: FuelResponse? = null
        var decisions = DecisionsResponse()
        var agents = AgentsResponse()
        var alerts = AlertsResponse()

        for ((providerId, _) in reports) {
            val adapter = adapters[providerId]
            if (adapter is ConnectedApiProviderAdapter) {
                fuel = adapter.lastFuel
                decisions = adapter.lastDecisions
                agents = adapter.lastAgents
                alerts = adapter.lastAlerts
                // Sync Junie balance from remote dashboard to local settings
                adapter.lastFuel?.junie?.let { junie ->
                    junie.balance?.let { saveStringSetting(FuelSettingsKeys.JUNIE_BALANCE, it.toString()) }
                    junie.license?.let { saveStringSetting(FuelSettingsKeys.JUNIE_LICENSE, it) }
                    junie.lastChecked?.let { saveStringSetting(FuelSettingsKeys.JUNIE_LAST_CHECKED, it.toString()) }
                }
                break
            }
        }

        // ── Load local decisions if no ConnectedApi provided them ──────
        if (decisions.decisions.isEmpty()) {
            decisions = DecisionsResponse(decisions = onFetchDecisions?.invoke() ?: emptyList())
        }

        // ── Standalone alert generation ──────────────────────────────────
        // If no connected API (orchestrator) is providing alerts, generate
        // them locally from provider fuel percentages.
        val generatedAlerts = mutableListOf<String>()
        for ((providerId, report) in reports) {
            val pct = report.remainingPct
            if (pct != null && report.available) {
                val name = report.displayName.ifBlank { providerId }
                when {
                    pct < 10 -> generatedAlerts.add("CRITICAL: $name at $pct%")
                    pct < 25 -> generatedAlerts.add("WARNING: $name at $pct%")
                }
            }
        }
        // Merge: if the orchestrator provided alerts, use those + generated.
        // Otherwise, use generated alone.
        if (generatedAlerts.isNotEmpty()) {
            alerts = AlertsResponse(alerts.alerts + generatedAlerts)
        }

        // ── Standalone decision engine ───────────────────────────────────
        // If no connected API is providing a recommended model, run the
        // local decision engine to pick the best model from current fuel state.
        val currentFuel = fuel
        val recommendedModel = currentFuel?.recommendedModel ?: ""
        val shouldRunDecisionEngine = recommendedModel.isBlank()

        if (shouldRunDecisionEngine && reports.isNotEmpty()) {
            val fuelConfig = buildFuelConfigFromReports(reports, _state.value.settings.providers)
            val providerStates = buildProviderStates(reports)
            val burnRateVal = BurnRateCalculator.compute(FuelHistoryStore.load())

            val decision = decideModel(
                config = fuelConfig,
                providerStates = providerStates,
                burnRate = burnRateVal,
                taskFloor = Complexity.MEDIUM,
                upgradeBenefit = 0.6,
            )

            if (decision != null) {
                // Set the recommended model so the banner shows it
                fuel = (fuel ?: FuelResponse()).copy(
                    recommendedModel = decision.handle,
                    burnRatePctPerHr = burnRateVal ?: 0.0,
                )

                // Log decision to SQLite via callback
                onDecisionLogged?.invoke(
                    "system",
                    decision.handle,
                    decision.provider,
                    decision.tier.name.lowercase(),
                    Complexity.MEDIUM.name.lowercase(),
                    decision.utilizationRatio ?: 0.0,
                    decision.headroom,
                    decision.reason,
                )
            }
        }

        // Update burn rate history from provider reports
        for ((providerId, report) in reports) {
            if (report.type == ProviderType.WINDOW_CREDIT && report.remainingPct != null) {
                val usedPct = 100 - report.remainingPct
                val snapshot = FuelSnapshot(
                    timestampMs = epochMillis(),
                    tokensUsedPct = usedPct,
                )
                FuelHistoryStore.add(snapshot)
            }
        }

        // Compute burn rate from history
        val history = FuelHistoryStore.load()
        val dataPoints = history.size
        val burnRate = BurnRateCalculator.compute(history)

        _state.value = _state.value.copy(
            providerReports = reports,
            providerErrors = errors,
            fuel = fuel,
            decisions = decisions,
            agents = agents,
            alerts = alerts,
            isLoading = false,
            lastUpdated = epochMillis(),
            burnRate = if (burnRate != null && burnRate > 0) burnRate else null,
            dataPointCount = dataPoints,
        )

        // Merge orchestrator agents into acpAgents so they show in the AgentPanel
        // (works on both desktop and mobile — desktop gets ACP agents via main.kt,
        // mobile gets orchestrator agents via ConnectedApiProviderAdapter)
        if (agents.agents.isNotEmpty()) {
            val orchestratorAgents = agents.agents.map { agent ->
                AcpAgentDisplay(
                    id = agent.agentId,
                    name = agent.name,
                    currentModel = agent.currentModel.ifBlank { null },
                    availableModels = emptyList(),
                    currentMode = null,
                    availableModes = emptyList(),
                    status = "connected",
                    capabilities = emptyList(),
                )
            }
            // Merge: keep existing ACP/MCP-registered agents, add orchestrator ones that aren't duplicates
            val existing = _state.value.acpAgents.toMutableList()
            for (agent in orchestratorAgents) {
                if (existing.none { it.id == agent.id || it.name.equals(agent.name, ignoreCase = true) }) {
                    existing.add(agent)
                }
            }
            if (existing.size != _state.value.acpAgents.size) {
                _state.value = _state.value.copy(acpAgents = existing)
            }
        }
    }

    fun close() {
        stopPolling()
        closeAdapters()
    }

    // --- Standalone decision engine helpers ---

    /**
     * Builds a FuelConfig for the decision engine from current provider reports.
     * Maps provider kinds to known model tiers (best-effort heuristic).
     */
    private fun buildFuelConfigFromReports(
        reports: Map<String, ProviderReport>,
        providerConfigs: List<ProviderConfig>,
    ): FuelConfig {
        val providers = reports.map { (id, report) ->
            val config = providerConfigs.find { it.id == id }
            val name = config?.resolvedDisplayName() ?: report.displayName
            FuelProviderConfig(
                name = name,
                priority = when (config?.kind) {
                    ProviderKind.ZAI -> 1
                    ProviderKind.LETTA_CLOUD -> 2
                    ProviderKind.OPENAI -> 3
                    ProviderKind.ANTHROPIC -> 3
                    ProviderKind.DEEPSEEK -> 4
                    ProviderKind.GROQ -> 5
                    ProviderKind.MISTRAL -> 5
                    else -> 9
                },
                models = config?.kind?.let { kind ->
                    defaultModelsFor(kind)
                } ?: emptyList(),
            )
        }
        return FuelConfig(providers = providers)
    }

    /**
     * Maps provider states for the decision engine.
     */
    private fun buildProviderStates(
        reports: Map<String, ProviderReport>,
    ): Map<String, ProviderStateInfo> {
        return reports.mapValues { (id, report) ->
            ProviderStateInfo(
                name = report.displayName,
                remainingPct = report.remainingPct,
                available = report.available,
                resetsAt = if (report.resetsAt != null) {
                    mapOf("main" to report.resetsAt)
                } else {
                    emptyMap()
                },
            )
        }
    }
}
