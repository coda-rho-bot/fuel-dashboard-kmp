package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.Decision
import com.angussoftware.fueldashboard.network.FuelApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Connected-mode data parity (mobile): fetches the remote dashboard's
 * /dashboard snapshot and maps it into the local display types. Mobile has
 * no local repositories — the Remote Dashboard is its only source of
 * metered usage, waste, and event data.
 */
internal class RemoteDashboardSnapshot(
    val metered: MeteredUsageWindows?,
    val intelligence: IntelligenceData?,
    /** Provider gauges from the remote dashboard (quota % + reset windows). */
    val providers: List<RemoteProviderGauge> = emptyList(),
    /** Recent decisions (additive snapshot section; empty on older servers). */
    val decisions: List<Decision> = emptyList(),
    /** Active alerts (additive snapshot section; empty on older servers). */
    val alerts: List<String> = emptyList(),
)

/**
 * One provider gauge line from the remote /dashboard snapshot — feeds the
 * Fuel tab provider cards and the status notification in connected mode.
 */
internal data class RemoteProviderGauge(
    val id: String,
    val name: String,
    val kind: String,
    val remainingPct: Int?,
    val resetsAt: Long?,
    val windowHours: Double,
)

internal object RemoteDashboardFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(adapterUrl: String, apiKey: String): RemoteDashboardSnapshot? {
        return try {
            val client = FuelApiClient(normalize(adapterUrl), apiKey)
            val body = client.getDashboardSnapshot() ?: return null
            parse(body)
        } catch (_: Exception) {
            null // non-fatal: mobile stays on whatever it had
        }
    }

    /**
     * Parses a `/dashboard` snapshot body into display types.
     * Extracted for round-trip testing against [DashboardSnapshot.build].
     */
    internal fun parse(body: String): RemoteDashboardSnapshot? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            RemoteDashboardSnapshot(
                metered = mapMetered(root),
                intelligence = mapIntelligence(root),
                providers = mapProviders(root),
                decisions = mapDecisions(root),
                alerts = mapAlerts(root),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Decisions array (additive section; absent on pre-consolidation servers). */
    private fun mapDecisions(root: kotlinx.serialization.json.JsonObject): List<Decision> {
        val arr = root["decisions"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            Decision(
                id = o["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                agentId = o["agent_id"]?.jsonPrimitive?.contentOrNull ?: "",
                modelHandle = o["model_handle"]?.jsonPrimitive?.contentOrNull ?: "",
                provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "",
                tier = o["tier"]?.jsonPrimitive?.contentOrNull ?: "",
                complexity = o["complexity"]?.jsonPrimitive?.contentOrNull ?: "",
                utilizationRatio = o["utilization_ratio"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                headroom = o["headroom"]?.jsonPrimitive?.intOrNull ?: 0,
                reason = o["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                timestamp = o["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    /** Alerts array of plain strings (additive section). */
    private fun mapAlerts(root: kotlinx.serialization.json.JsonObject): List<String> {
        val arr = root["alerts"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    /**
     * Parses the `providers` object map: id → {name, kind, remaining_pct,
     * resets_at, window_hours} (the DashboardSnapshot wire format).
     */
    private fun mapProviders(root: kotlinx.serialization.json.JsonObject): List<RemoteProviderGauge> {
        val providers = root["providers"]?.jsonObject ?: return emptyList()
        return providers.mapNotNull { (id, v) ->
            val o = v.jsonObject
            RemoteProviderGauge(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: id,
                kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "",
                remainingPct = o["remaining_pct"]?.jsonPrimitive?.intOrNull,
                resetsAt = o["resets_at"]?.jsonPrimitive?.longOrNull,
                windowHours = o["window_hours"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }
    }

    private fun normalize(url: String): String = url.trimEnd('/')

    private fun mapMetered(root: kotlinx.serialization.json.JsonObject): MeteredUsageWindows? {
        val usage = root["usage"]?.jsonObject ?: return null
        fun list(obj: kotlinx.serialization.json.JsonObject, label: String, isConv: Boolean) =
            obj[label]?.jsonObject?.mapNotNull { (k, v) ->
                val o = v.jsonObject
                if (isConv) {
                    ConversationUsageDisplay(
                        conversationId = k,
                        agentName = o["agent"]?.jsonPrimitive?.contentOrNull ?: "",
                        model = o["model"]?.jsonPrimitive?.contentOrNull ?: "",
                        inputTokens = o["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                        outputTokens = o["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                        requestCount = o["requests"]?.jsonPrimitive?.longOrNull ?: 0L,
                        creditCost = o["zai_credits"]?.jsonPrimitive?.doubleOrNull,
                        title = o["title"]?.jsonPrimitive?.contentOrNull,
                    )
                } else {
                    null to MeteredUsageDisplay(
                        label = k,
                        inputTokens = o["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                        outputTokens = o["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                        requestCount = o["requests"]?.jsonPrimitive?.longOrNull ?: 0L,
                        creditCost = o["zai_credits"]?.jsonPrimitive?.doubleOrNull,
                    )
                }
            } ?: emptyList()

        // by-source/model: label→display; by-conversation: id→display
        fun srcList(label: String): List<MeteredUsageDisplay> =
            usage[label]?.jsonObject?.mapNotNull { (k, v) ->
                val o = v.jsonObject
                MeteredUsageDisplay(
                    label = o["agent"]?.jsonPrimitive?.contentOrNull ?: k,
                    inputTokens = o["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    outputTokens = o["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    requestCount = o["requests"]?.jsonPrimitive?.longOrNull ?: 0L,
                    creditCost = o["zai_credits"]?.jsonPrimitive?.doubleOrNull,
                )
            } ?: emptyList()

        fun convList(label: String): List<ConversationUsageDisplay> =
            usage[label]?.jsonObject?.mapNotNull { (k, v) ->
                val o = v.jsonObject
                ConversationUsageDisplay(
                    conversationId = k,
                    agentName = o["agent"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = o["model"]?.jsonPrimitive?.contentOrNull ?: "",
                    inputTokens = o["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    outputTokens = o["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    requestCount = o["requests"]?.jsonPrimitive?.longOrNull ?: 0L,
                    creditCost = o["zai_credits"]?.jsonPrimitive?.doubleOrNull,
                    title = o["title"]?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyList()

        fun agentModelList(label: String): List<AgentModelUsageDisplay> =
            usage[label]?.jsonObject?.mapNotNull { (_, v) ->
                val o = v.jsonObject
                AgentModelUsageDisplay(
                    agentName = o["agent"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = o["model"]?.jsonPrimitive?.contentOrNull ?: "",
                    inputTokens = o["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    outputTokens = o["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    requestCount = o["requests"]?.jsonPrimitive?.longOrNull ?: 0L,
                    creditCost = o["zai_credits"]?.jsonPrimitive?.doubleOrNull,
                )
            } ?: emptyList()

        return MeteredUsageWindows(
            bySource24h = srcList("by_source_24h"),
            byModel24h = srcList("by_model_24h"),
            bySource7d = srcList("by_source_7d"),
            byModel7d = srcList("by_model_7d"),
            byConversation24h = convList("by_conversation_24h"),
            byConversation7d = convList("by_conversation_7d"),
            byAgentModel24h = agentModelList("by_agent_model_24h"),
            byAgentModel7d = agentModelList("by_agent_model_7d"),
        )
    }

    private fun mapIntelligence(root: kotlinx.serialization.json.JsonObject): IntelligenceData? {
        val waste = root["waste"]?.jsonObject?.mapNotNull { (pid, v) ->
            val o = v.jsonObject
            val daily = o["daily"]?.jsonObject?.mapNotNull { (dayKey, dv) ->
                val d = dv.jsonObject
                FuelIntelligence.DailyWaste(
                    dayStart = dayKey.toLongOrNull() ?: return@mapNotNull null,
                    windows = d["windows"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                    observed = d["observed"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                    estimated = d["estimated"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                    wastedPctAvg = d["wasted_pct_avg"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    anyExhausted = d["any_exhausted"]?.jsonPrimitive?.contentOrNull == "true",
                )
            } ?: emptyList()
            FuelIntelligence.ProviderWaste(
                providerId = pid,
                providerName = o["provider_name"]?.jsonPrimitive?.contentOrNull ?: pid,
                windowMs = o["window_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                daily = daily,
                wastedPctAvg = o["wasted_pct_avg"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        } ?: emptyList()

        val events = root["fuel_events"]?.jsonObject?.get("events")?.jsonObject?.mapNotNull { (_, v) ->
            val e = v.jsonObject
            val type = when (e["type"]?.jsonPrimitive?.contentOrNull) {
                "FUEL_DROP" -> FuelIntelligence.FuelEventType.FUEL_DROP
                "MODEL_SWITCH" -> FuelIntelligence.FuelEventType.MODEL_SWITCH
                "RECOMMENDATION" -> FuelIntelligence.FuelEventType.RECOMMENDATION
                else -> return@mapNotNull null
            }
            FuelIntelligence.FuelEvent(
                timestamp = e["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                type = type,
                description = e["description"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }?.sortedByDescending { it.timestamp } ?: emptyList()

        // Advisor advice: remote state string → local Advice (best-effort)
        val advice = root["advisor"]?.jsonObject?.let { adv ->
            when (adv["state"]?.jsonPrimitive?.contentOrNull) {
                "surplus" -> FuelAdvisor.Advice.Surplus(FuelAdvisor.QuotaRegime(1, 0, 100.0, 0.0, null))
                "at_risk" -> FuelAdvisor.Advice.AtRisk(FuelAdvisor.QuotaRegime(1, 0, 0.0, 0.0, null), null, emptyList())
                "persistent_pressure" -> FuelAdvisor.Advice.PersistentPressure(FuelAdvisor.QuotaRegime(1, 0, 0.0, 0.0, null), emptyList())
                "healthy" -> FuelAdvisor.Advice.Healthy(FuelAdvisor.QuotaRegime(1, 0, 100.0, 0.0, null), 50.0)
                else -> null
            }
        }

        return IntelligenceData(wasteByProvider = waste, fuelEvents = events, advice = advice)
    }
}
