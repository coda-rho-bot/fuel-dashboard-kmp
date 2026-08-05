package com.angussoftware.fueldashboard.model

/**
 * Abstraction over different fuel data sources.
 *
 * - [com.angussoftware.fueldashboard.network.ZaiDirectFuelSource] polls the z.ai quota API directly.
 * - [com.angussoftware.fueldashboard.network.OrchestratorFuelSource] wraps the existing orchestrator API client.
 *
 * Both produce a [FuelResponse] so the UI layer is agnostic to the data source.
 */
interface FuelSource {
    suspend fun getFuel(): FuelResponse
    val displayName: String
}

/**
 * Which fuel source mode the dashboard is operating in.
 */
enum class FuelSourceMode {
    DIRECT,
    CONNECTED,
}

/**
 * Provider identifier — currently only z.ai, but structured for future expansion.
 */
enum class FuelProvider(val displayName: String) {
    ZAI("z.ai"),
}

/**
 * Serializable fuel snapshot for local history / burn-rate computation.
 */
@kotlinx.serialization.Serializable
data class FuelSnapshot(
    @kotlinx.serialization.SerialName("ts")
    val timestampMs: Long,
    @kotlinx.serialization.SerialName("tp")
    val tokensUsedPct: Int,
    @kotlinx.serialization.SerialName("sp")
    val sessionUsedPct: Int? = null,
)

/**
 * All settings related to fuel source configuration.
 */
data class FuelSettings(
    val mode: FuelSourceMode = FuelSourceMode.CONNECTED,
    val provider: FuelProvider = FuelProvider.ZAI,
    val providerApiKey: String = "",
    val orchestratorUrl: String = "http://127.0.0.1:8321",
) {
    /**
     * True if the app has enough configuration to start polling.
     * - DIRECT: requires a non-empty providerApiKey.
     * - CONNECTED: requires a non-empty orchestratorUrl.
     */
    val isConfigured: Boolean
        get() = when (mode) {
            FuelSourceMode.DIRECT -> providerApiKey.isNotBlank()
            FuelSourceMode.CONNECTED -> orchestratorUrl.isNotBlank()
        }
}
