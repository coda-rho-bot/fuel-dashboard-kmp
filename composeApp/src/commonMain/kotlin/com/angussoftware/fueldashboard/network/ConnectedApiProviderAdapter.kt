package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow

/**
 * Adapter for the "Connected API" (orchestrator) provider kind.
 *
 * Wraps [FuelApiClient] as a [ProviderAdapter] so the orchestrator
 * is treated as just another provider in the multi-provider list.
 *
 * The adapter polls the orchestrator's /fuel endpoint and also fetches
 * supplementary data (decisions, agents, alerts) in the same call.
 * After [poll], the ViewModel reads [lastFuel], [lastDecisions],
 * [lastAgents], and [lastAlerts] to populate the supplementary panels.
 */
class ConnectedApiProviderAdapter(
    override val providerId: String,
    private val baseUrl: String,
    private val customDisplayName: String = "",
) : ProviderAdapter {
    override val displayName: String = customDisplayName.ifBlank { "Remote Dashboard" }
    override val providerType: ProviderType = ProviderType.WINDOW_CREDIT

    private val client = FuelApiClient(baseUrl)

    /** Supplementary data from the last poll — read by the ViewModel. */
    @Volatile
    var lastFuel: FuelResponse? = null
        private set

    @Volatile
    var lastDecisions: DecisionsResponse = DecisionsResponse()
        private set

    @Volatile
    var lastAgents: AgentsResponse = AgentsResponse()
        private set

    @Volatile
    var lastAlerts: AlertsResponse = AlertsResponse()
        private set

    override suspend fun poll(): ProviderReport {
        val fuel = client.getFuel()
        val decisions = runCatching { client.getDecisions(20) }.getOrElse { DecisionsResponse() }
        val agents = runCatching { client.getAgents() }.getOrElse { AgentsResponse() }
        val alerts = runCatching { client.getAlerts() }.getOrElse { AlertsResponse() }

        // Store supplementary data for ViewModel to read
        lastFuel = fuel
        lastDecisions = decisions
        lastAgents = agents
        lastAlerts = alerts

        // Aggregate all orchestrator providers into windows
        val windows = mutableListOf<ReportWindow>()
        var overallRemaining: Int? = null

        for ((providerName, provider) in fuel.providers) {
            if (provider.remainingPct != null) {
                val pct = provider.remainingPct
                // Pick the first available overall percentage as the headline
                if (overallRemaining == null) overallRemaining = pct

                windows.add(
                    ReportWindow(
                        name = providerName,
                        remainingPct = pct,
                        resetsAt = provider.resetMs,
                        windowHours = 0.0,
                    ),
                )
            }
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = ProviderType.WINDOW_CREDIT,
            remainingPct = overallRemaining,
            windows = windows,
            rawDisplay = if (fuel.providers.isNotEmpty()) {
                fuel.providers.entries.joinToString(", ") { (name, p) ->
                    "$name: ${p.remainingPct ?: "—"}%"
                }
            } else "",
        )
    }

    override fun close() = Unit
}
