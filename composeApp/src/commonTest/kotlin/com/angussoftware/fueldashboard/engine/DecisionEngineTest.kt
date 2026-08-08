package com.angussoftware.fueldashboard.engine

import com.angussoftware.fueldashboard.util.epochMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionEngineTest {
    @Test
    fun selectTierKeepsFloorWhenUtilizationIsUnknown() {
        val decision = decisionFor(
            provider = provider(models = allTierModels()),
            burnRate = null,
            taskFloor = Complexity.MEDIUM,
            upgradeBenefit = 1.0,
        )

        assertEquals(Complexity.MEDIUM, decision.tier)
        assertEquals("medium", decision.handle)
        assertNull(decision.utilizationRatio)
    }

    @Test
    fun selectTierUpgradesAtLowAndModerateBalancedUtilizationThresholds() {
        val lowUtilization = decisionFor(
            provider = provider(models = allTierModels()),
            state = state(remainingPct = 100),
            burnRate = 2.0,
            taskFloor = Complexity.MEDIUM,
            upgradeBenefit = 0.3,
        )
        val moderateUtilization = decisionFor(
            provider = provider(models = allTierModels()),
            state = state(remainingPct = 100),
            burnRate = 6.0,
            taskFloor = Complexity.MEDIUM,
            upgradeBenefit = 0.5,
        )

        assertEquals(Complexity.HEAVY, lowUtilization.tier)
        assertEquals(Complexity.HEAVY, moderateUtilization.tier)
        assertTrue(lowUtilization.utilizationRatio!! < 0.5)
        assertTrue(moderateUtilization.utilizationRatio!! in 0.5..0.8)
    }

    @Test
    fun selectTierRespectsModerateBenefitThresholdAndKeepsTierWithinBudget() {
        val noModerateUpgrade = decisionFor(
            provider = provider(models = allTierModels()),
            state = state(remainingPct = 100),
            burnRate = 6.0,
            taskFloor = Complexity.MEDIUM,
            upgradeBenefit = 0.49,
        )
        val withinBudget = decisionFor(
            provider = provider(models = allTierModels()),
            state = state(remainingPct = 100),
            burnRate = 9.0,
            taskFloor = Complexity.HEAVY,
            upgradeBenefit = 1.0,
        )

        assertEquals(Complexity.MEDIUM, noModerateUpgrade.tier)
        assertEquals(Complexity.HEAVY, withinBudget.tier)
    }

    @Test
    fun selectTierUsesAggressiveAndSpendDownStrategiesOutsideBalancedWindow() {
        val earlyWindow = decisionFor(
            provider = provider(models = allTierModels()),
            state = state(remainingPct = 100, windowPosition = 0.1),
            burnRate = 6.0,
            taskFloor = Complexity.MEDIUM,
            upgradeBenefit = 0.3,
        )
        val lateWindow = decisionFor(
            provider = provider(models = allTierModels()),
            state = state(remainingPct = 100, windowPosition = 0.95),
            burnRate = 6.0,
            taskFloor = Complexity.MEDIUM,
            upgradeBenefit = 0.3,
        )

        assertEquals(Complexity.HEAVY, earlyWindow.tier)
        assertEquals(Complexity.HEAVY, lateWindow.tier)
    }

    @Test
    fun decideModelUsesPriorityThenSkipsUnavailableAndExhaustedLimitedProviders() {
        val unavailable = provider(name = "unavailable", priority = 1, models = allTierModels())
        val exhausted = provider(name = "exhausted", priority = 2, models = allTierModels())
        val available = provider(name = "available", priority = 3, models = allTierModels())

        val decision = assertNotNull(
            decideModel(
                config = FuelConfig(listOf(available, exhausted, unavailable)),
                providerStates = mapOf(
                    "unavailable" to state(available = false),
                    "exhausted" to state(remainingPct = 5),
                    "available" to state(remainingPct = 80),
                ),
                burnRate = 2.0,
                taskFloor = Complexity.MEDIUM,
                upgradeBenefit = 0.0,
            ),
        )

        assertEquals("available", decision.provider)
        assertEquals("medium", decision.handle)
    }

    @Test
    fun findModelForTierSelectsNearestAvailableTierAndFallsBackToFirstModel() {
        val nearestTier = decisionFor(
            provider = provider(models = listOf(FuelModel("heavy-only", Complexity.HEAVY))),
            taskFloor = Complexity.MEDIUM,
        )
        val firstModelFallback = decisionFor(
            provider = provider(
                models = listOf(
                    FuelModel("light-first", Complexity.LIGHT),
                    FuelModel("heavy-second", Complexity.HEAVY),
                ),
            ),
            taskFloor = Complexity.TRIVIAL,
        )

        assertEquals("heavy-only", nearestTier.handle)
        assertEquals(Complexity.MEDIUM, nearestTier.tier)
        assertEquals("light-first", firstModelFallback.handle)
    }

    @Test
    fun assessProviderTreatsUnlimitedProvidersAsFullAndLimitedProvidersAsProjectedFuel() {
        val unlimited = provider(name = "unlimited", priority = 1, unlimited = true, models = allTierModels())
        val limited = provider(name = "limited", priority = 2, models = allTierModels())

        val unlimitedDecision = assertNotNull(
            decideModel(
                config = FuelConfig(listOf(unlimited, limited)),
                providerStates = mapOf(
                    "unlimited" to state(remainingPct = 0),
                    "limited" to state(remainingPct = 80),
                ),
                burnRate = 100.0,
                taskFloor = Complexity.MEDIUM,
                upgradeBenefit = 0.0,
            ),
        )
        val limitedDecision = decisionFor(
            provider = limited,
            state = state(remainingPct = 80),
            burnRate = 2.0,
            taskFloor = Complexity.MEDIUM,
        )

        assertEquals("unlimited", unlimitedDecision.provider)
        assertNull(unlimitedDecision.utilizationRatio)
        assertEquals(100.0, unlimitedDecision.projectedRemaining)
        assertTrue(limitedDecision.utilizationRatio != null)
        assertTrue(limitedDecision.projectedRemaining < 80.0)
    }

    @Test
    fun decideModelReturnsFallbackAndNullWhenNoRoutableModelExists() {
        val fallback = assertNotNull(
            decideModel(
                config = FuelConfig(
                    listOf(
                        provider(name = "fallback", models = listOf(FuelModel("first", Complexity.LIGHT))),
                        provider(name = "empty", priority = 1, models = emptyList()),
                    ),
                ),
                providerStates = mapOf(
                    "fallback" to state(available = false),
                    "empty" to state(available = false),
                ),
                burnRate = null,
                taskFloor = Complexity.HEAVY,
                upgradeBenefit = 0.0,
            ),
        )
        val noModels = decideModel(
            config = FuelConfig(listOf(provider(models = emptyList()))),
            providerStates = emptyMap(),
            burnRate = null,
            taskFloor = Complexity.MEDIUM,
            upgradeBenefit = 0.0,
        )

        assertEquals("first", fallback.handle)
        assertEquals("fallback — no viable provider found", fallback.reason)
        assertNull(noModels)
    }

    @Test
    fun estimateComplexityClassifiesEmptyShortTextAndCodeMessages() {
        assertEquals(Complexity.MEDIUM to 0.6, estimateComplexity(""))
        assertEquals(Complexity.TRIVIAL to 0.0, estimateComplexity("hello"))
        assertEquals(Complexity.MEDIUM to 0.6, estimateComplexity("fun greet() = println(\"hi\")"))
        assertEquals(Complexity.HEAVY to 0.0, estimateComplexity("```kotlin\nfun greet() = println(\"hi\")\n```"))
    }

    @Test
    fun estimateComplexityUsesKeywordsAndLengthBoundaries() {
        assertEquals(Complexity.MEDIUM to 0.6, estimateComplexity("Please debug this."))
        assertEquals(Complexity.HEAVY to 0.0, estimateComplexity("debug " + "x".repeat(100)))
        assertEquals(Complexity.LIGHT to 0.2, estimateComplexity("x".repeat(100)))
        assertEquals(Complexity.MEDIUM to 0.6, estimateComplexity("x".repeat(300)))
    }

    private fun decisionFor(
        provider: FuelProviderConfig,
        state: ProviderStateInfo? = null,
        burnRate: Double? = null,
        taskFloor: Complexity,
        upgradeBenefit: Double = 0.0,
    ): FuelDecision = assertNotNull(
        decideModel(
            config = FuelConfig(listOf(provider)),
            providerStates = state?.let { mapOf(provider.name to it) }.orEmpty(),
            burnRate = burnRate,
            taskFloor = taskFloor,
            upgradeBenefit = upgradeBenefit,
        ),
    )

    private fun provider(
        name: String = "provider",
        priority: Int = 1,
        models: List<FuelModel>,
        unlimited: Boolean = false,
    ) = FuelProviderConfig(name, priority, models, unlimited)

    private fun state(
        remainingPct: Int = 100,
        available: Boolean = true,
        windowPosition: Double? = null,
    ) = ProviderStateInfo(
        name = "state",
        remainingPct = remainingPct,
        available = available,
        windowPosition = windowPosition,
        resetsAt = mapOf("window" to epochMillis() + 36_000_000),
    )

    private fun allTierModels() = Complexity.ORDER.map { tier ->
        FuelModel(tier.name.lowercase(), tier)
    }
}