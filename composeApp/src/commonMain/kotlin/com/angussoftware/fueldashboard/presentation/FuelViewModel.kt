package com.angussoftware.fueldashboard.presentation

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
import com.angussoftware.fueldashboard.network.FuelApiClient
import com.angussoftware.fueldashboard.network.LettaCloudProviderAdapter
import com.angussoftware.fueldashboard.network.OrchestratorFuelSource
import com.angussoftware.fueldashboard.network.ZaiProviderAdapter
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import com.angussoftware.fueldashboard.storage.BurnRateCalculator
import com.angussoftware.fueldashboard.storage.FuelHistoryStore
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
) {
    /** True if no providers are configured yet (first-run). */
    val needsSetup: Boolean get() = !settings.hasAnyConfig

    /** All configured providers that have API keys. */
    val activeProviders: List<ProviderConfig>
        get() = settings.providers.filter { it.isConfigured }

    /** Whether orchestrator (connected mode) data is available. */
    val isOrchestratorConnected: Boolean
        get() = settings.orchestratorEnabled && settings.orchestratorUrl.isNotBlank()
}

class FuelViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val adapters = mutableMapOf<String, ProviderAdapter>()
    private var apiClient: FuelApiClient? = null
    private var orchestratorFuelSource: OrchestratorFuelSource? = null

    private val _state = MutableStateFlow(DashboardState(isLoading = false))
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        val settings = FuelSettingsStore.loadMultiProvider()
        _state.value = _state.value.copy(settings = settings)
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

    // --- Internals ---

    private fun activateAdapters(settings: MultiProviderSettings) {
        // Create provider adapters
        for (config in settings.providers) {
            if (!config.isConfigured) continue
            val adapter = createAdapter(config) ?: continue
            adapters[config.id] = adapter
        }

        // Create orchestrator client if enabled
        if (settings.orchestratorEnabled && settings.orchestratorUrl.isNotBlank()) {
            apiClient = FuelApiClient(settings.orchestratorUrl)
            orchestratorFuelSource = OrchestratorFuelSource(apiClient!!)
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
        }
    }

    private fun closeAdapters() {
        adapters.values.forEach { runCatching { it.close() } }
        adapters.clear()
        apiClient?.close()
        apiClient = null
        orchestratorFuelSource = null
    }

    /**
     * Poll interval: 5 minutes for direct providers, 30s if orchestrator is connected.
     */
    private fun pollIntervalMs(): Long {
        val hasOrchestrator = _state.value.isOrchestratorConnected
        return if (hasOrchestrator) 30_000L else 300_000L
    }

    private suspend fun refresh() {
        if (adapters.isEmpty() && orchestratorFuelSource == null) {
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

        // Fetch orchestrator data if connected
        var fuel: FuelResponse? = null
        var decisions = DecisionsResponse()
        var agents = AgentsResponse()
        var alerts = AlertsResponse()

        val orchSource = orchestratorFuelSource
        val orchClient = apiClient
        if (_state.value.isOrchestratorConnected && orchSource != null && orchClient != null) {
            try {
                fuel = orchSource.getFuel()
                decisions = runCatching { orchClient.getDecisions(20) }.getOrElse { DecisionsResponse() }
                agents = runCatching { orchClient.getAgents() }.getOrElse { AgentsResponse() }
                alerts = runCatching { orchClient.getAlerts() }.getOrElse { AlertsResponse() }
            } catch (e: Exception) {
                // Orchestrator error — keep provider reports, just mark orch as failing
                errors["orchestrator"] = e.message ?: "Orchestrator connection error"
            }
        }

        // Update burn rate history from direct-mode provider reports
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
