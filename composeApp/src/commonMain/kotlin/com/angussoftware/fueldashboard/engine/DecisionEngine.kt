package com.angussoftware.fueldashboard.engine





/**
 * Model complexity tier — maps to fuel allocation levels.
 * Ordered from cheapest (trivial) to most expensive (heavy).
 */
enum class Complexity {
    TRIVIAL, LIGHT, MEDIUM, HEAVY;

    companion object {
        val ORDER = listOf(TRIVIAL, LIGHT, MEDIUM, HEAVY)
        fun fromString(s: String): Complexity =
            entries.firstOrNull { it.name.lowercase() == s.lowercase() } ?: MEDIUM
    }
}

/**
 * A model available on a provider, with its tier classification.
 */
data class FuelModel(
    val name: String,
    val tier: Complexity,
    val bareName: String? = null,
)

/**
 * A fuel provider configuration.
 */
data class FuelProviderConfig(
    val name: String,
    val priority: Int = 99,
    val models: List<FuelModel> = emptyList(),
)

/**
 * Per-provider fuel state from monitoring.
 */
data class ProviderStateInfo(
    val name: String,
    val remainingPct: Int? = null,
    val available: Boolean = true,
    val resetsAt: Map<String, Long?> = emptyMap(),
)

/**
 * The result of a model routing decision.
 */
data class FuelDecision(
    val handle: String,
    val provider: String,
    val tier: Complexity,
    val reasoningEffort: String? = null,
    val reason: String,
    val utilizationRatio: Double? = null,
    val headroom: Int = 0,
    val projectedRemaining: Double = 0.0,
)

/**
 * Full fuel configuration — providers, models, strategy.
 */
data class FuelConfig(
    val providers: List<FuelProviderConfig> = emptyList(),
    val strategy: Map<String, Any> = emptyMap(),
)

/**
 * NOTE (adversarial review M6, Sep 2026): the tier-selection engine
 * (decideModel / selectTier / assessProvider / pickReasoningEffort) was
 * removed — zero production callers since inception; the decisions table
 * only ever held orchestrator-recommendation echoes (see
 * FuelViewModel.onDecisionLogged). The config TYPES above remain live
 * (sync payloads, UI plumbing). If local tier routing is ever built,
 * recover the engine from git history (pre-Sep-2026).
 */
