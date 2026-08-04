package com.angussoftware.fueldashboard.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FuelResponse(
    val ts: Long? = null,
    val providers: Map<String, Provider> = emptyMap(),
    @SerialName("provider_resets")
    val providerResets: Map<String, Map<String, Long?>?> = emptyMap(),
    @SerialName("provider_windows")
    val providerWindows: Map<String, ProviderWindowInfo> = emptyMap(),
    @SerialName("burn_rate_pct_per_hr")
    val burnRatePctPerHr: Double = 0.0,
    @SerialName("window_positions")
    val windowPositions: Map<String, Double> = emptyMap(),
    @SerialName("recommended_model")
    val recommendedModel: String = "",
    @SerialName("mod_managed")
    val modManaged: Map<String, Boolean> = emptyMap(),
    @SerialName("surplus_alert")
    val surplusAlert: Boolean = false,
)

@Serializable
data class Provider(
    val name: String = "",
    @SerialName("remaining_pct")
    val remainingPct: Int? = null,
    val available: Boolean = true,
    @SerialName("reset_ms")
    val resetMs: Long? = null,
    val windows: Map<String, Window> = emptyMap(),
)

@Serializable
data class Window(
    @SerialName("remaining_pct")
    val remainingPct: Int? = null,
    @SerialName("resets_at")
    val resetsAt: Long? = null,
    @SerialName("window_hours")
    val windowHours: Double = 0.0,
)

@Serializable
data class ProviderWindowInfo(
    @SerialName("window_hours")
    val windowHours: Double = 0.0,
    val position: Double = 0.0,
)

@Serializable
data class DecisionsResponse(
    val decisions: List<Decision> = emptyList(),
)

@Serializable
data class Decision(
    val id: Long = 0,
    @SerialName("agent_id")
    val agentId: String = "",
    @SerialName("model_handle")
    val modelHandle: String = "",
    val provider: String = "",
    val tier: String = "",
    val complexity: String = "",
    @SerialName("utilization_ratio")
    val utilizationRatio: Double = 0.0,
    val headroom: Int = 0,
    val reason: String = "",
    val timestamp: Long = 0,
)

@Serializable
data class AgentsResponse(
    val agents: List<FleetAgent> = emptyList(),
)

@Serializable
data class FleetAgent(
    @SerialName("agentId")
    val agentId: String = "",
    val name: String = "",
    @SerialName("currentModel")
    val currentModel: String = "",
    @SerialName("lastTaskComplexity")
    val lastTaskComplexity: String = "",
    @SerialName("fuelAllocation")
    val fuelAllocation: Int = 0,
    @SerialName("activeSubagents")
    val activeSubagents: Int = 0,
)

@Serializable
data class AlertsResponse(
    val alerts: List<FuelAlert> = emptyList(),
)

@Serializable
data class FuelAlert(
    val message: String = "",
    val severity: String = "info",
    val timestamp: Long? = null,
)
