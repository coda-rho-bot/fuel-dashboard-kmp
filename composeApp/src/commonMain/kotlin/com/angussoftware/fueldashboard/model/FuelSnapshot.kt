package com.angussoftware.fueldashboard.model

/**
 * Serializable fuel snapshot for local history / burn-rate computation.
 * (Lived in FuelSource.kt until that interface — dead since the
 * multi-provider refactor — was removed.)
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
