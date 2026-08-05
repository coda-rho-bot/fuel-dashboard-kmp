package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.FuelSettings
import com.angussoftware.fueldashboard.model.FuelSnapshot
import com.angussoftware.fueldashboard.model.FuelSourceMode
import com.angussoftware.fueldashboard.model.FuelSource
import com.angussoftware.fueldashboard.network.FuelApiClient
import com.angussoftware.fueldashboard.network.OrchestratorFuelSource
import com.angussoftware.fueldashboard.network.ZaiDirectFuelSource
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import com.angussoftware.fueldashboard.storage.BurnRateCalculator
import com.angussoftware.fueldashboard.storage.FuelHistoryStore
import com.angussoftware.fueldashboard.util.epochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardState(
    val fuel: FuelResponse? = null,
    val decisions: DecisionsResponse = DecisionsResponse(),
    val agents: AgentsResponse = AgentsResponse(),
    val alerts: AlertsResponse = AlertsResponse(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastUpdated: Long = 0L,
    val settings: FuelSettings = FuelSettings(),
    val burnRate: Double? = null,
    val dataPointCount: Int = 0,
) {
    val baseUrl: String get() = settings.orchestratorUrl

    /** True if no fuel source has been configured yet (first-run). */
    val needsSetup: Boolean get() = !settings.isConfigured
}

class FuelViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private var fuelSource: FuelSource? = null
    private var apiClient: FuelApiClient? = null // orchestrator-only, for agents/decisions/alerts

    private val _state = MutableStateFlow(DashboardState(isLoading = false))
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        val settings = FuelSettingsStore.load()
        _state.value = _state.value.copy(settings = settings)
        if (settings.isConfigured) {
            activateFuelSource(settings)
        }
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        val currentSettings = _state.value.settings
        if (!currentSettings.isConfigured) return

        pollJob = scope.launch {
            // Initial immediate poll
            refresh()
            val interval = pollIntervalMs(currentSettings)
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

    /**
     * Updates fuel source settings and restarts polling with the new source.
     */
    fun updateSettings(newSettings: FuelSettings) {
        stopPolling()
        closeClients()

        FuelSettingsStore.save(newSettings)

        _state.value = _state.value.copy(
            settings = newSettings,
            isLoading = true,
            fuel = null,
            decisions = DecisionsResponse(),
            agents = AgentsResponse(),
            alerts = AlertsResponse(),
            error = null,
            burnRate = null,
            dataPointCount = 0,
        )

        if (newSettings.isConfigured) {
            activateFuelSource(newSettings)
            startPolling()
        }
    }

    // --- Backward-compatible API ---

    fun updateBaseUrl(url: String) {
        val current = _state.value.settings
        updateSettings(current.copy(orchestratorUrl = url.trim()))
    }

    // --- Internals ---

    private fun activateFuelSource(settings: FuelSettings) {
        when (settings.mode) {
            FuelSourceMode.DIRECT -> {
                fuelSource = when (settings.provider) {
                    else -> ZaiDirectFuelSource(settings.providerApiKey)
                }
                apiClient = null
            }
            FuelSourceMode.CONNECTED -> {
                apiClient = FuelApiClient(settings.orchestratorUrl)
                fuelSource = OrchestratorFuelSource(apiClient!!)
            }
        }
    }

    private fun closeClients() {
        (fuelSource as? ZaiDirectFuelSource)?.close()
        apiClient?.close()
        fuelSource = null
        apiClient = null
    }

    private fun pollIntervalMs(settings: FuelSettings): Long {
        return when (settings.mode) {
            FuelSourceMode.DIRECT -> 300_000L  // 5 minutes
            FuelSourceMode.CONNECTED -> 30_000L // 30 seconds
        }
    }

    private suspend fun refresh() {
        val source = fuelSource ?: run {
            _state.value = _state.value.copy(isLoading = false, error = "No fuel source configured")
            return
        }

        try {
            val fuel = source.getFuel()

            // Fetch orchestrator-only data if connected
            var decisions = DecisionsResponse()
            var agents = AgentsResponse()
            var alerts = AlertsResponse()

            if (_state.value.settings.mode == FuelSourceMode.CONNECTED) {
                val client = apiClient
                if (client != null) {
                    decisions = runCatching { client.getDecisions(20) }.getOrElse { DecisionsResponse() }
                    agents = runCatching { client.getAgents() }.getOrElse { AgentsResponse() }
                    alerts = runCatching { client.getAlerts() }.getOrElse { AlertsResponse() }
                }
            }

            // Update history + burn rate (direct mode only — orchestrator computes its own)
            var burnRate = fuel.burnRatePctPerHr
            var dataPoints = 0
            if (_state.value.settings.mode == FuelSourceMode.DIRECT) {
                val tokensUsedPct = fuel.providers["z.ai"]?.let { provider ->
                    provider.remainingPct?.let { remaining -> 100 - remaining }
                }
                if (tokensUsedPct != null) {
                    val snapshot = FuelSnapshot(
                        timestampMs = epochMillis(),
                        tokensUsedPct = tokensUsedPct,
                    )
                    FuelHistoryStore.add(snapshot)
                }

                val history = FuelHistoryStore.load()
                dataPoints = history.size
                burnRate = BurnRateCalculator.compute(history) ?: 0.0
            }

            _state.value = _state.value.copy(
                fuel = fuel.copy(burnRatePctPerHr = burnRate),
                decisions = decisions,
                agents = agents,
                alerts = alerts,
                isLoading = false,
                error = null,
                lastUpdated = epochMillis(),
                burnRate = if (burnRate > 0) burnRate else null,
                dataPointCount = dataPoints,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = e.message ?: "Unknown error",
            )
        }
    }

    fun close() {
        stopPolling()
        closeClients()
    }
}
