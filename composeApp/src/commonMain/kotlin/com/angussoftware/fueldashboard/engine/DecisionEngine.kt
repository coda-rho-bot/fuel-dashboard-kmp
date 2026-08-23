package com.angussoftware.fueldashboard.engine

import kotlin.math.max
import kotlin.math.min
import com.angussoftware.fueldashboard.util.formatRoot

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

private fun Complexity.downgrade(n: Int): Complexity {
    val idx = Complexity.ORDER.indexOf(this)
    return Complexity.ORDER[max(0, idx - n)]
}

private fun Complexity.upgrade(n: Int): Complexity {
    val idx = Complexity.ORDER.indexOf(this)
    return Complexity.ORDER[min(3, idx + n)]
}

private fun selectTier(
    floor: Complexity,
    utilizationRatio: Double?,
    benefit: Double,
): Complexity {
    if (utilizationRatio == null) return floor

    return when {
        utilizationRatio < 0.5 -> {
            if (benefit >= 0.3) floor.upgrade(1) else floor
        }
        utilizationRatio < 0.8 -> {
            if (benefit >= 0.5) floor.upgrade(1) else floor
        }
        utilizationRatio <= 1.2 -> floor
        utilizationRatio <= 1.5 -> floor.downgrade(1)
        else -> floor.downgrade(2)
    }
}

private fun findModelForTier(provider: FuelProviderConfig, tier: Complexity): FuelModel? {
    var model = provider.models.firstOrNull { it.tier == tier }
    if (model != null) return model

    val idx = Complexity.ORDER.indexOf(tier)
    for (off in 1..3) {
        for (ci in listOf(idx + off, idx - off)) {
            if (ci in Complexity.ORDER.indices) {
                model = provider.models.firstOrNull { it.tier == Complexity.ORDER[ci] }
                if (model != null) return model
            }
        }
    }
    return provider.models.firstOrNull()
}

private fun pickReasoningEffort(complexity: Complexity, utilization: Double?): String {
    if (utilization != null && utilization > 1.2) {
        return if (complexity == Complexity.HEAVY) "medium" else "low"
    }
    return when (complexity) {
        Complexity.HEAVY -> "high"
        Complexity.MEDIUM -> "medium"
        else -> "low"
    }
}

private data class Assessment(
    val provider: FuelProviderConfig,
    val remaining: Double,
    val utilizationRatio: Double?,
    val projectedRemaining: Double,
)

/**
 * Assesses a provider's fuel state, computing utilization ratio and projected remaining.
 *
 * NOTE: burnRate is a single global value (aggregated across all providers), not
 * per-provider. This means the utilization ratio is computed by dividing a global
 * burn rate by each provider's individual optimal burn rate. This is an inherent
 * limitation — providers have different billing cycles (window-credit vs spend-budget),
 * so the ratio is only meaningful for providers that share the same reset window.
 * A per-provider burn rate would require tracking consumption per provider, which
 * is an architectural change beyond the current scope.
 */
private fun assessProvider(
    provider: FuelProviderConfig,
    remainingPct: Int?,
    available: Boolean,
    resetsAt: Map<String, Long?>,
    burnRate: Double?,
): Assessment {
    val remaining = when {
        remainingPct != null -> remainingPct.toDouble()
        else -> 50.0 // unknown — neutral default, not 100%
    }

    var utilizationRatio: Double? = null
    var projectedRemaining = remaining

    if (burnRate != null) {
        var hoursToReset: Double? = null
        val now = com.angussoftware.fueldashboard.util.epochMillis()

        for ((_, ts) in resetsAt) {
            if (ts != null) {
                val h = max(0.01, (ts - now) / 3_600_000.0)
                if (hoursToReset == null || h < hoursToReset) hoursToReset = h
            }
        }

        if (hoursToReset != null && hoursToReset > 0) {
            val optimalBurn = remaining / hoursToReset
            if (optimalBurn > 0 && burnRate > 0) {
                utilizationRatio = burnRate / optimalBurn
            }
            projectedRemaining = remaining - burnRate * hoursToReset
        }
    }

    return Assessment(provider, remaining, utilizationRatio, projectedRemaining)
}

/**
 * Core decision function — picks the optimal model given fuel state + complexity.
 *
 * Iterates providers by priority, finds the first viable one, selects the
 * appropriate tier based on utilization, and returns a routing decision.
 *
 * @param config fuel configuration (providers, models)
 * @param providerStates current fuel state per provider
 * @param burnRate current burn rate in pct/hour
 * @param taskFloor minimum complexity tier required by the task
 * @param upgradeBenefit how much benefit upgrading provides (0.0-1.0)
 * @return routing decision, or null if no viable model found
 */
fun decideModel(
    config: FuelConfig,
    providerStates: Map<String, ProviderStateInfo>,
    burnRate: Double?,
    taskFloor: Complexity,
    upgradeBenefit: Double,
): FuelDecision? {
    for (provider in config.providers.sortedBy { it.priority }) {
        val state = providerStates[provider.name]
        if (state != null && !state.available) continue

        val assessment = assessProvider(
            provider = provider,
            remainingPct = state?.remainingPct,
            available = state?.available ?: true,
            resetsAt = state?.resetsAt ?: emptyMap(),
            burnRate = burnRate,
        )

        if (assessment.projectedRemaining <= 0) continue

        val tier = selectTier(
            taskFloor,
            assessment.utilizationRatio,
            upgradeBenefit,
        )

        val model = findModelForTier(provider, tier)
        if (model != null) {
            return FuelDecision(
                handle = model.name,
                provider = provider.name,
                tier = tier,
                reasoningEffort = pickReasoningEffort(taskFloor, assessment.utilizationRatio),
                reason = buildReason(tier, taskFloor, assessment.utilizationRatio, assessment),
                utilizationRatio = assessment.utilizationRatio,
                headroom = (100 - assessment.projectedRemaining).toInt(),
                projectedRemaining = assessment.projectedRemaining,
            )
        }
    }

    // Fallback: first model of first provider
    val fallback = config.providers.firstOrNull()?.models?.firstOrNull()
    return fallback?.let {
        FuelDecision(
            handle = it.name,
            provider = config.providers.first().name,
            tier = it.tier,
            reason = "fallback — no viable provider found",
            utilizationRatio = null,
            headroom = 0,
            projectedRemaining = 0.0,
        )
    }
}

private fun buildReason(
    tier: Complexity,
    floor: Complexity,
    utilization: Double?,
    assessment: Assessment,
): String {
    val parts = mutableListOf<String>()
    if (tier != floor) {
        if (Complexity.ORDER.indexOf(tier) > Complexity.ORDER.indexOf(floor)) {
            parts.add("upgraded ${floor.name.lowercase()}→${tier.name.lowercase()} (surplus/abundant)")
        } else {
            parts.add("downgraded ${floor.name.lowercase()}→${tier.name.lowercase()} (fuel pressure)")
        }
    } else {
        parts.add("${tier.name.lowercase()} (task floor)")
    }
    if (utilization != null) {
        parts.add("ratio ${formatRoot("%.2f", utilization)}")
    }
    parts.add("${formatRoot("%.0f", assessment.remaining)}% remaining")
    return parts.joinToString(", ")
}


