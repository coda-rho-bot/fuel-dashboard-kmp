package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.network.FuelApiClient
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
    val baseUrl: String = "http://127.0.0.1:8321",
)

class FuelViewModel(
    initialBaseUrl: String = "http://127.0.0.1:8321",
    private val pollIntervalMs: Long = 30_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var apiClient = FuelApiClient(initialBaseUrl)

    private val _state = MutableStateFlow(DashboardState(baseUrl = initialBaseUrl))
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                refresh()
                delay(pollIntervalMs)
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

    fun updateBaseUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed == _state.value.baseUrl) return

        stopPolling()
        apiClient.close()
        apiClient = FuelApiClient(trimmed)

        _state.value = _state.value.copy(
            baseUrl = trimmed,
            isLoading = true,
            fuel = null,
            decisions = DecisionsResponse(),
            agents = AgentsResponse(),
            alerts = AlertsResponse(),
            error = null,
        )
        startPolling()
    }

    private suspend fun refresh() {
        try {
            val fuel = apiClient.getFuel()
            val decisions = apiClient.getDecisions(20)
            val agents = apiClient.getAgents()
            val alerts = apiClient.getAlerts()

            _state.value = _state.value.copy(
                fuel = fuel,
                decisions = decisions,
                agents = agents,
                alerts = alerts,
                isLoading = false,
                error = null,
                lastUpdated = epochMillis(),
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
        apiClient.close()
    }
}
