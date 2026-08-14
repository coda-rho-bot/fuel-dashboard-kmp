package com.angussoftware.fueldashboard.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ── Conversation-level savings tests ──

    private fun convUsage(
        convId: String,
        agent: String,
        model: String,
        input: Long,
        output: Long,
    ) = ConversationUsageDisplay(
        conversationId = convId,
        agentName = agent,
        model = model,
        inputTokens = input,
        outputTokens = output,
        requestCount = 1,
        creditCost = zaiCreditCost(model, input, output),
    )

    @Test
    fun conversationSavingsNeedsTwoModels() {
        val all = listOf(convUsage("c1", "Coda", "glm-5.2", 1000, 200))
        assertEquals(emptyList(), UsageRecommender.conversationSavings(all))
    }

    @Test
    fun conversationSavingsIdentifiesExpensiveConversations() {
        val convs = listOf(
            convUsage("c1", "Coda", "glm-5.2", 90_000, 10_000),   // expensive
            convUsage("c2", "Coda", "glm-5.2", 50_000, 5_000),    // expensive
            convUsage("c3", "Beacon", "glm-4.7", 20_000, 5_000),  // cheap
        )
        val savings = UsageRecommender.conversationSavings(convs)
        assertEquals(2, savings.size)
        // Sorted by absolute credit savings descending — c1 (90k input) saves more than c2 (50k input)
        assertEquals("c1", savings[0].conversationId)
        assertEquals("c2", savings[1].conversationId)
        // All should recommend switching from glm-5.2 to glm-4.7
        savings.forEach {
            assertEquals("glm-5.2", it.fromModel)
            assertEquals("glm-4.7", it.toModel)
            assertTrue(it.savingsFraction > 0)
        }
    }

    @Test
    fun conversationSavingsExcludesCheapModelConversations() {
        val convs = listOf(
            convUsage("c1", "Coda", "glm-5.2", 90_000, 10_000),
            convUsage("c2", "Beacon", "glm-4.7", 20_000, 5_000),
        )
        val savings = UsageRecommender.conversationSavings(convs)
        // Only c1 (running the expensive model) should appear
        assertEquals(1, savings.size)
        assertEquals("c1", savings[0].conversationId)
    }

    @Test
    fun conversationSavingsEmptyForUnknownModels() {
        val convs = listOf(
            convUsage("c1", "Coda", "gpt-4o", 1000, 200),
            convUsage("c2", "Beacon", "claude-3", 500, 100),
        )
        assertEquals(emptyList<UsageRecommender.ConversationSavings>(), UsageRecommender.conversationSavings(convs))
    }
}
