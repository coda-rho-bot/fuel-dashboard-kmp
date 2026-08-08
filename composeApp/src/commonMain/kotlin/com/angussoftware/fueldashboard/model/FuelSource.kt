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
