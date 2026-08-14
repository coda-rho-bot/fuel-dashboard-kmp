package com.angussoftware.fueldashboard.presentation

/**
 * Recommender v2 — metered-data model switch recommendations.
 *
 * Unlike the old decision engine (which recommended from tier heuristics),
 * this works from METERED token counts and published credit multipliers:
 * given per-model usage over a window, it projects what the expensive
 * model's traffic would cost if served by the cheaper model.
 *
 * Pure function over display data — no I/O, fully testable. The caller
 * decides how to act (manual switch via Agents tab today; an automated
 * executor can subscribe later).
 */
object UsageRecommender {

    data class SwitchRecommendation(
        val fromModel: String,
        val toModel: String,
        /** Total tokens (in+out) metered on fromModel in the window. */
        val tokensInWindow: Long,
        /** Exact credit cost of that traffic at fromModel's multipliers. */
        val currentCreditCost: Double,
        /** Projected cost of the SAME token mix at toModel's multipliers. */
        val projectedCreditCost: Double,
        /** Savings fraction 0..1. */
        val savingsFraction: Double,
    )

    /**
     * Recommends moving the highest-credit-cost model's traffic to the
     * lowest-cost known model. Returns null when fewer than two known
     * models have metered usage in the window (not enough data to compare
     * honestly — the status UI handles that case).
     */
    fun recommendSwitch(byModel: List<MeteredUsageDisplay>): SwitchRecommendation? {
        val known = byModel.filter {
            ZaiCreditMultipliers.known(it.label) && (it.inputTokens + it.outputTokens) > 0
        }
        if (known.size < 2) return null

        val from = known.maxByOrNull { it.creditCost ?: 0.0 } ?: return null
        val to = known.minByOrNull { it.creditCost ?: Double.MAX_VALUE } ?: return null
        if (from.label == to.label) return null

        val currentCost = from.creditCost
            ?: ZaiCreditMultipliers.cost(from.label, from.inputTokens, from.outputTokens)
            ?: return null
        val projectedCost = ZaiCreditMultipliers.cost(to.label, from.inputTokens, from.outputTokens)
            ?: return null
        if (projectedCost >= currentCost) return null

        return SwitchRecommendation(
            fromModel = from.label,
            toModel = to.label,
            tokensInWindow = from.inputTokens + from.outputTokens,
            currentCreditCost = currentCost,
            projectedCreditCost = projectedCost,
            savingsFraction = 1.0 - projectedCost / currentCost,
        )
    }
}
