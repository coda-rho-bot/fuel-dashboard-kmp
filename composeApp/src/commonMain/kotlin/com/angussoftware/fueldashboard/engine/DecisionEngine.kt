package com.angussoftware.fueldashboard.engine

import kotlin.math.max
import kotlin.math.min

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
    val unlimited: Boolean = false,
)

/**
 * Per-provider fuel state from monitoring.
 */
data class ProviderStateInfo(
    val name: String,
    val remainingPct: Int? = null,
    val available: Boolean = true,
    val windowPosition: Double? = null,
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

private fun getWindowStrategy(windowPosition: Double?): String {
    if (windowPosition == null) return "balanced"
    if (windowPosition < 0.2) return "aggressive"
    if (windowPosition > 0.9) return "spend-down"
    return "balanced"
}

private fun selectTier(
    floor: Complexity,
    utilizationRatio: Double?,
    benefit: Double,
    windowPosition: Double?,
): Complexity {
    if (utilizationRatio == null) return floor

    val ws = getWindowStrategy(windowPosition)
    if (ws == "aggressive" || ws == "spend-down") {
        if (benefit >= 0.3) return floor.upgrade(1)
        return floor
    }

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
    val windowPosition: Double?,
)

private fun assessProvider(
    provider: FuelProviderConfig,
    remainingPct: Int?,
    available: Boolean,
    windowPosition: Double?,
    resetsAt: Map<String, Long?>,
    burnRate: Double?,
): Assessment {
    val remaining = when {
        provider.unlimited -> 100.0
        remainingPct != null -> remainingPct.toDouble()
        else -> 50.0
    }

    var utilizationRatio: Double? = null
    var projectedRemaining = remaining

    if (!provider.unlimited && burnRate != null) {
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

    return Assessment(provider, remaining, utilizationRatio, projectedRemaining, windowPosition)
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
            windowPosition = state?.windowPosition,
            resetsAt = state?.resetsAt ?: emptyMap(),
            burnRate = burnRate,
        )

        if (!provider.unlimited && assessment.projectedRemaining <= 0) continue

        val tier = selectTier(
            taskFloor,
            assessment.utilizationRatio,
            upgradeBenefit,
            assessment.windowPosition,
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
        parts.add("ratio ${"%.2f".format(utilization)}")
    }
    parts.add("${"%.0f".format(assessment.remaining)}% remaining")
    return parts.joinToString(", ")
}

/**
 * Estimates task complexity from message text.
 *
 * @param text the incoming message
 * @return pair of (minimum tier, upgrade benefit 0.0-1.0)
 */
fun estimateComplexity(text: String): Pair<Complexity, Double> {
    if (text.isEmpty()) return Complexity.MEDIUM to 0.6

    val hasCode = text.contains("```") || text.contains("def ") || text.contains("fun ")
    val hasCodeLang = Regex("```(kotlin|python|typescript|java|rust)", RegexOption.IGNORE_CASE).containsMatchIn(text)
    val heavyKw = Regex(
        "\\b(review|refactor|architect|design|debug|implement|build|create|fix|error|crash|broken|failing|migration|analyze|race|concurrent|leak|optimize)\\b",
        RegexOption.IGNORE_CASE,
    )

    return when {
        hasCodeLang || (hasCode && text.length > 500) -> Complexity.HEAVY to 0.0
        heavyKw.containsMatchIn(text) && text.length > 100 -> Complexity.HEAVY to 0.0
        heavyKw.containsMatchIn(text) -> Complexity.MEDIUM to 0.6
        hasCode || text.length > 1000 -> Complexity.MEDIUM to 0.6
        text.length < 100 -> Complexity.TRIVIAL to 0.0
        text.length < 300 -> Complexity.LIGHT to 0.2
        else -> Complexity.MEDIUM to 0.6
    }
}
