package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
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
import com.angussoftware.fueldashboard.network.LettaCloudProviderAdapter
import com.angussoftware.fueldashboard.network.MistralProviderAdapter
import com.angussoftware.fueldashboard.network.OpenAIProviderAdapter
import com.angussoftware.fueldashboard.network.ZaiProviderAdapter
import com.angussoftware.fueldashboard.settings.AgentSettingsStore
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
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
) {
    /** All configured providers (have enough info to poll). */
    val activeProviders: List<ProviderConfig>
        get() = settings.providers.filter { it.isConfigured }

    /** Whether any connected API (orchestrator) is active — used for agents/alerts panel visibility. */
    val hasConnectedApi: Boolean
        get() = settings.providers.any { it.kind == ProviderKind.CONNECTED_API && it.isConfigured }
}

class FuelViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val adapters = mutableMapOf<String, ProviderAdapter>()

    private val _state = MutableStateFlow(DashboardState(isLoading = false))
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        val settings = FuelSettingsStore.loadMultiProvider()
        val agents = AgentSettingsStore.load()
        _state.value = _state.value.copy(settings = settings, agentSettings = agents)
        if (settings.hasAnyConfig) {
            activateAdapters(settings)
        }
    }

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
     * Callback invoked when agent settings change (add/remove).
     * Set from main.kt (desktop) to restart AcpAgentManager with new config.
     * Null on platforms without ACP support (Android).
     */
    var onAgentSettingsChanged: ((AgentSettings) -> Unit)? = null

    /**
     * Push ACP agent display data into dashboard state. Called from main.kt
     * when the AcpAgentManager StateFlow emits updates.
     */
    fun updateAcpAgents(agents: List<AcpAgentDisplay>) {
        _state.value = _state.value.copy(acpAgents = agents)
    }

    // --- Settings updates ---

    /**
     * Updates multi-provider settings and restarts polling with the new adapters.
     */
    fun updateSettings(newSettings: MultiProviderSettings) {
        stopPolling()
        closeAdapters()

        FuelSettingsStore.saveMultiProvider(newSettings)

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
        )

        if (newSettings.hasAnyConfig) {
            activateAdapters(newSettings)
            startPolling()
        }
    }

    /**
     * Adds a new provider to settings.
     */
    fun addProvider(kind: ProviderKind, apiKey: String, displayName: String = "", serverUrl: String = "") {
        val current = _state.value.settings
        val newProvider = ProviderConfig(
            id = FuelSettingsStore.generateProviderId(),
            kind = kind,
            apiKey = apiKey,
            displayName = displayName,
            serverUrl = serverUrl,
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
        val updated = _state.value.agentSettings.copy(
            agents = _state.value.agentSettings.agents.filterNot { it.id == agentId },
        )
        AgentSettingsStore.save(updated)
        _state.value = _state.value.copy(agentSettings = updated)
        onAgentSettingsChanged?.invoke(updated)
    }

    /**
     * Imports synced settings from a QR code scan.
     *
     * Replaces all current providers and theme with the imported data.
     */
    fun importSyncedSettings(syncData: com.angussoftware.fueldashboard.model.SettingsSyncData) {
        updateSettings(syncData.toMultiProviderSettings())

        // Apply theme settings
        val themeController = com.angussoftware.fueldashboard.settings.ThemeController
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
                monthlyBudgetUsd = null,
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.ANTHROPIC -> AnthropicProviderAdapter(
                providerId = config.id,
                apiKey = config.apiKey,
                baseUrl = config.resolvedServerUrl(),
                monthlyBudgetUsd = null,
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
                monthlyBudgetUsd = null,
                customDisplayName = config.resolvedDisplayName(),
            )
            ProviderKind.CONNECTED_API -> ConnectedApiProviderAdapter(
                providerId = config.id,
                baseUrl = config.resolvedServerUrl(),
                customDisplayName = config.resolvedDisplayName(),
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
                break
            }
        }

        // Update burn rate history from provider reports
        var burnRate: Double? = null
        var dataPoints = 0
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
        dataPoints = history.size
        burnRate = BurnRateCalculator.compute(history)

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
    }

    fun close() {
        stopPolling()
        closeAdapters()
    }
}
