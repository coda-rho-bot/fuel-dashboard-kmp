package com.angussoftware.fueldashboard.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UsageRecommenderTest {

    private fun usage(model: String, input: Long, output: Long) = MeteredUsageDisplay(
        label = model,
        inputTokens = input,
        outputTokens = output,
        requestCount = 1,
        creditCost = zaiCreditCost(model, input, output),
    )

    @Test
    fun needsTwoModelsToRecommend() {
        assertNull(UsageRecommender.recommendSwitch(listOf(usage("glm-5.2", 1000, 200))))
        assertNull(UsageRecommender.recommendSwitch(emptyList()))
    }

    @Test
    fun unknownModelsDoNotCount() {
        val mixed = listOf(
            usage("glm-5.2", 1000, 200),
            MeteredUsageDisplay("gpt-4o", 500, 100, 1, creditCost = null),
        )
        assertNull(UsageRecommender.recommendSwitch(mixed))
    }

    @Test
    fun recommendsCheaperModelForExpensiveTraffic() {
        // glm-5.2 (=5.3 tier: 6.9/24) vs glm-4.7 (4.6/16)
        val rec = UsageRecommender.recommendSwitch(
            listOf(
                usage("glm-5.2", 90_000, 10_000),
                usage("glm-4.7", 20_000, 5_000),
            ),
        )
        assertNotNull(rec)
        assertEquals("glm-5.2", rec.fromModel)
        assertEquals("glm-4.7", rec.toModel)
        // Current: 90000*6.9 + 10000*24 = 621000 + 240000 = 861000
        assertEquals(861_000.0, rec.currentCreditCost, 0.01)
        // Projected: 90000*4.6 + 10000*16 = 414000 + 160000 = 574000
        assertEquals(574_000.0, rec.projectedCreditCost, 0.01)
        // Savings: 1 - 574000/861000 ≈ 0.333
        assertEquals(0.333, rec.savingsFraction, 0.01)
    }

    @Test
    fun noRecommendationWhenCheaperModelCostsMore() {
        // Same effective cost (both map to the same tier)
        val rec = UsageRecommender.recommendSwitch(
            listOf(
                usage("glm-5.3", 1000, 100),
                usage("glm-5.2", 2000, 200),
            ),
        )
        assertNull(rec)
    }
}
