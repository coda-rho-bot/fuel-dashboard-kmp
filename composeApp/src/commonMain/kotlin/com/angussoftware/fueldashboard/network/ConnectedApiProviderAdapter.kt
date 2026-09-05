package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.AgentsResponse
import com.angussoftware.fueldashboard.model.AlertsResponse
import com.angussoftware.fueldashboard.model.DecisionsResponse
import com.angussoftware.fueldashboard.model.FleetAgent
import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.JunieBalanceData
import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.presentation.RemoteDashboardFetcher
import com.angussoftware.fueldashboard.presentation.RemoteDashboardSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Adapter for the "Connected API" (orchestrator) provider kind.
 *
 * Wraps [FuelApiClient] as a [ProviderAdapter] so the orchestrator is
 * treated as just another provider in the multi-provider list.
 *
 * SINGLE network fetch per poll: /dashboard is the complete dashboard
 * state (gauges, decisions, agents, alerts, metered, waste) — the old
 * 4-call-per-poll pattern (fuel + decisions + agents + alerts) plus the
 * ViewModel's separate /dashboard fetch made 5 requests per refresh where
 * 1 suffices. The parsed snapshot is shared with the ViewModel via
 * [lastSnapshot]; supplementary panels read [lastFuel], [lastDecisions],
 * [lastAgents], [lastAlerts] as before.
 */
class ConnectedApiProviderAdapter(
    override val providerId: String,
    private val baseUrl: String,
    private val customDisplayName: String = "",
    private val apiKey: String = "",
) : ProviderAdapter {
    override val displayName: String = customDisplayName.ifBlank { "Remote Dashboard" }
    override val providerType: ProviderType = ProviderType.WINDOW_CREDIT

    private val client = FuelApiClient(baseUrl, apiKey)

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

    /** The parsed /dashboard snapshot from the last poll — shared, not re-fetched. */
    @Volatile
    internal var lastSnapshot: RemoteDashboardSnapshot? = null
        private set

    override suspend fun poll(): ProviderReport {
        val body = client.getDashboardSnapshot()
            ?: throw IllegalStateException("Remote dashboard unreachable: $baseUrl")
        val snapshot = RemoteDashboardFetcher.parse(body)
            ?: throw IllegalStateException("Remote dashboard response unparseable: $baseUrl")

        lastSnapshot = snapshot
        lastDecisions = DecisionsResponse(decisions = snapshot.decisions)
        lastAlerts = AlertsResponse(alerts = snapshot.alerts)
        lastAgents = AgentsResponse(agents = mapAgents(body))
        lastFuel = mapFuel(body)

        // Aggregate all orchestrator gauges into windows
        val windows = snapshot.providers.mapNotNull { p ->
            p.remainingPct?.let { pct ->
                ReportWindow(
                    name = p.name,
                    remainingPct = pct,
                    resetsAt = p.resetsAt,
                    windowHours = p.windowHours,
                )
            }
        }
        val headline = windows.firstOrNull { it.remainingPct != null }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = ProviderType.WINDOW_CREDIT,
            remainingPct = headline?.remainingPct,
            resetsAt = headline?.resetsAt,
            windowHours = headline?.windowHours ?: 0.0,
            windows = windows,
            rawDisplay = if (windows.isNotEmpty()) {
                windows.joinToString(", ") { "${it.name}: ${it.remainingPct}%" }
            } else "",
        )
    }

    /**
     * Maps the snapshot's agents section (name → {id, status, session_model})
     * into FleetAgent rows. Fields the section doesn't carry keep their
     * defaults — the common UI renders name/model only.
     */
    private fun mapAgents(body: String): List<FleetAgent> {
        return try {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
            val agents = root["agents"]?.jsonObject ?: return emptyList()
            agents.entries.mapNotNull { (name, v) ->
                val o = v.jsonObject
                FleetAgent(
                    agentId = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = name,
                    currentModel = o["session_model"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * FuelResponse for legacy consumers. The snapshot carries the Junie
     * balance section (the old /fuel endpoint's job) — parse it here so the
     * ViewModel's Junie sync path keeps working; provider-level gauges live
     * in the snapshot windows instead of the legacy providers map.
     */
    private fun mapFuel(body: String): FuelResponse {
        return try {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
            val junieObj = root["junie"]?.jsonObject
            FuelResponse(
                // Empty junie object (server with no Junie state) → null,
                // not balance=0.0 — a phantom $0.00 card would persist on
                // the receiver (review 1965).
                junie = junieObj?.takeIf { it.isNotEmpty() }?.let { j ->
                    JunieBalanceData(
                        balance = j["balance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        license = j["license"]?.jsonPrimitive?.contentOrNull,
                        lastChecked = j["last_checked"]?.jsonPrimitive?.longOrNull,
                    )
                },
            )
        } catch (_: Exception) {
            FuelResponse()
        }
    }

    override fun close() = Unit
}
