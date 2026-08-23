package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.LettaQuotaResponse
import com.angussoftware.fueldashboard.model.LettaTierInfo
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ZaiQuotaData
import com.angussoftware.fueldashboard.model.ZaiQuotaLimit
import com.angussoftware.fueldashboard.model.ZaiQuotaResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture-based tests for adapter parser functions.
 *
 * Each adapter has a `buildReport` or `mapToProviderReport` function that transforms
 * a provider's API response DTO into a [com.angussoftware.fueldashboard.model.ProviderReport].
 * These tests verify the parsing logic using recorded API response fixtures — no network calls.
 *
 * Covers: z.ai, DeepSeek, Mistral, OpenAI, Anthropic, Letta Cloud, Groq.
 */
class AdapterFixtureTest {

    // -----------------------------------------------------------------------
    // z.ai
    // -----------------------------------------------------------------------

    @Test
    fun zai_tokensAndSessionLimits_correctRemainingPercentages() {
        val adapter = ZaiProviderAdapter("zai-test", "fake-key")
        val response = ZaiQuotaResponse(
            success = true,
            data = ZaiQuotaData(
                limits = listOf(
                    ZaiQuotaLimit(type = "TOKENS_LIMIT", percentage = 42, nextResetTime = 1700000000000L),
                    ZaiQuotaLimit(type = "SESSION_LIMIT", percentage = 10, nextResetTime = 1700000000000L),
                ),
            ),
        )
        val report = adapter.mapToProviderReport(response)

        assertEquals("zai-test", report.providerId)
        assertEquals("z.ai", report.displayName)
        assertEquals(ProviderType.WINDOW_CREDIT, report.type)
        assertEquals(58, report.remainingPct) // 100 - 42 = 58
        assertEquals(1700000000000L, report.resetsAt)
        assertEquals(5.0, report.windowHours)
        assertTrue(report.available)
        assertEquals(2, report.windows.size)

        val tokenWindow = report.windows[0]
        assertEquals("5h Token Window", tokenWindow.name)
        assertEquals(58, tokenWindow.remainingPct)
        assertEquals(1700000000000L, tokenWindow.resetsAt)
        assertEquals(5.0, tokenWindow.windowHours)
        assertFalse(tokenWindow.resetEstimated)

        val sessionWindow = report.windows[1]
        assertEquals("Session", sessionWindow.name)
        assertEquals(90, sessionWindow.remainingPct) // 100 - 10 = 90
    }

    @Test
    fun zai_onlyTokensLimit_singleWindow() {
        val adapter = ZaiProviderAdapter("zai-test", "fake-key")
        val response = ZaiQuotaResponse(
            success = true,
            data = ZaiQuotaData(
                limits = listOf(
                    ZaiQuotaLimit(type = "TOKENS_LIMIT", percentage = 80, nextResetTime = null),
                ),
            ),
        )
        val report = adapter.mapToProviderReport(response)

        assertEquals(20, report.remainingPct) // 100 - 80 = 20
        assertEquals(1, report.windows.size)
        assertEquals("5h Token Window", report.windows[0].name)
        assertTrue(report.windows[0].resetEstimated) // nextResetTime was null
    }

    @Test
    fun zai_emptyLimits_notAvailable() {
        val adapter = ZaiProviderAdapter("zai-test", "fake-key")
        val response = ZaiQuotaResponse(success = true, data = ZaiQuotaData(limits = emptyList()))
        val report = adapter.mapToProviderReport(response)

        assertFalse(report.available)
        assertEquals(0, report.windows.size)
    }

    @Test
    fun zai_fullUsage_zeroRemaining() {
        val adapter = ZaiProviderAdapter("zai-test", "fake-key")
        val response = ZaiQuotaResponse(
            success = true,
            data = ZaiQuotaData(
                limits = listOf(
                    ZaiQuotaLimit(type = "TOKENS_LIMIT", percentage = 100, nextResetTime = 1700000000000L),
                ),
            ),
        )
        val report = adapter.mapToProviderReport(response)

        assertEquals(0, report.remainingPct)
    }

    // -----------------------------------------------------------------------
    // DeepSeek
    // -----------------------------------------------------------------------

    @Test
    fun deepSeek_availableBalance_correctDollarFields() {
        val adapter = DeepSeekProviderAdapter("deepseek-test", "fake-key")
        val balance = BalanceData(
            isAvailable = true,
            currency = "USD",
            totalBalance = 10.05,
            grantedBalance = 10.00,
            toppedUpBalance = 0.05,
        )
        val report = adapter.buildReport(balance)

        assertEquals("deepseek-test", report.providerId)
        assertEquals("DeepSeek", report.displayName)
        assertEquals(ProviderType.SPEND_BUDGET, report.type)
        assertTrue(report.available)
        assertEquals(0.0, report.usedDollars)
        assertEquals(10.05, report.limitDollars)
        assertEquals(1, report.windows.size)
        assertEquals("Credit Balance", report.windows[0].name)
        assertTrue(report.rawDisplay.contains("$10.05"))
        assertTrue(report.rawDisplay.contains("granted: $10.00"))
        assertTrue(report.rawDisplay.contains("topped up: $0.05"))
    }

    @Test
    fun deepSeek_nullBalance_unavailable() {
        val adapter = DeepSeekProviderAdapter("deepseek-test", "fake-key")
        val report = adapter.buildReport(null)

        assertFalse(report.available)
        assertEquals("Balance unavailable", report.rawDisplay)
    }

    @Test
    fun deepSeek_unavailableBalance_marksUnavailable() {
        val adapter = DeepSeekProviderAdapter("deepseek-test", "fake-key")
        val balance = BalanceData(
            isAvailable = false,
            currency = "USD",
            totalBalance = 0.0,
            grantedBalance = 0.0,
            toppedUpBalance = 0.0,
        )
        val report = adapter.buildReport(balance)

        assertFalse(report.available)
        assertTrue(report.rawDisplay.contains("INSUFFICIENT BALANCE"))
    }

    // -----------------------------------------------------------------------
    // Mistral
    // -----------------------------------------------------------------------

    @Test
    fun mistral_budgetAndRateLimit_multipleWindows() {
        val adapter = MistralProviderAdapter("mistral-test", "fake-key", monthlyBudgetUsd = 100.0)
        val usage = UsageData(
            totalCost = 25.0,
            currency = "EUR",
            startDate = "2026-08-01",
            endDate = "2026-08-23",
        )
        val spendLimit = SpendLimitData(
            monthlyLimitReached = false,
            currency = "EUR",
            lastPaymentFailure = false,
        )
        val rateLimit = MistralRateLimitData(
            requestsPerSecond = 5,
            maxTokensPerMinute = 10000,
        )
        val report = adapter.buildReport(usage, spendLimit, rateLimit)

        assertEquals("mistral-test", report.providerId)
        assertEquals("Mistral AI", report.displayName)
        assertEquals(ProviderType.SPEND_BUDGET, report.type)
        assertEquals(75, report.remainingPct) // 100 - 25% = 75
        assertTrue(report.available)
        assertEquals(25.0, report.usedDollars)
        assertEquals(100.0, report.limitDollars)

        // 3 windows: Monthly Budget + Requests/sec + Tokens/min
        assertEquals(3, report.windows.size)
        assertEquals("Monthly Budget", report.windows[0].name)
        assertEquals(75, report.windows[0].remainingPct)
        assertEquals("Requests/sec", report.windows[1].name)
        assertEquals("Tokens/min Limit", report.windows[2].name)
    }

    @Test
    fun mistral_limitReached_zeroRemaining() {
        val adapter = MistralProviderAdapter("mistral-test", "fake-key", monthlyBudgetUsd = 100.0)
        val usage = UsageData(
            totalCost = 0.0,
            currency = "EUR",
            startDate = null,
            endDate = null,
        )
        val spendLimit = SpendLimitData(
            monthlyLimitReached = true,
            currency = "EUR",
            lastPaymentFailure = false,
        )
        val report = adapter.buildReport(usage, spendLimit, null)

        assertEquals(0, report.remainingPct)
        assertTrue(report.rawDisplay.contains("LIMIT REACHED"))
    }

    @Test
    fun mistral_paymentFailure_shownInRawDisplay() {
        val adapter = MistralProviderAdapter("mistral-test", "fake-key", monthlyBudgetUsd = 100.0)
        val usage = UsageData(
            totalCost = 10.0,
            currency = "EUR",
            startDate = null,
            endDate = null,
        )
        val spendLimit = SpendLimitData(
            monthlyLimitReached = false,
            currency = "EUR",
            lastPaymentFailure = true,
        )
        val report = adapter.buildReport(usage, spendLimit, null)

        assertTrue(report.rawDisplay.contains("PAYMENT ISSUE"))
    }

    @Test
    fun mistral_noBudgetNoUsage_nullRemaining() {
        val adapter = MistralProviderAdapter("mistral-test", "fake-key", monthlyBudgetUsd = null)
        val report = adapter.buildReport(null, null, null)

        assertNull(report.remainingPct)
        assertFalse(report.available)
    }

    // -----------------------------------------------------------------------
    // OpenAI
    // -----------------------------------------------------------------------

    @Test
    fun openAI_budgetAndRateLimit_multipleWindows() {
        val adapter = OpenAIProviderAdapter("openai-test", "fake-key", monthlyBudgetUsd = 200.0)
        val limits = RateLimitData(
            limitRequests = 5000,
            remainingRequests = 4000,
            resetRequests = "60s",
            limitTokens = 200000,
            remainingTokens = 150000,
            resetTokens = "60s",
        )
        val report = adapter.buildReport(spend = 50.0, limits = limits)

        assertEquals("openai-test", report.providerId)
        assertEquals("OpenAI", report.displayName)
        assertEquals(ProviderType.SPEND_BUDGET, report.type)
        assertEquals(75, report.remainingPct) // 100 - (50/200 * 100) = 75
        assertTrue(report.available)
        assertEquals(50.0, report.usedDollars)
        assertEquals(200.0, report.limitDollars)

        // 3 windows: Monthly Budget + Requests/min + Tokens/min
        assertEquals(3, report.windows.size)
        assertEquals("Monthly Budget", report.windows[0].name)
        assertEquals(75, report.windows[0].remainingPct)
        assertEquals("Requests/min", report.windows[1].name)
        assertEquals(80, report.windows[1].remainingPct) // 4000/5000 * 100 = 80
        assertEquals("Tokens/min", report.windows[2].name)
        assertEquals(75, report.windows[2].remainingPct) // 150000/200000 * 100 = 75
    }

    @Test
    fun openAI_onlyRateLimit_noBudget() {
        val adapter = OpenAIProviderAdapter("openai-test", "fake-key", monthlyBudgetUsd = null)
        val limits = RateLimitData(
            limitRequests = 5000,
            remainingRequests = 2500,
            resetRequests = "60s",
            limitTokens = null,
            remainingTokens = null,
            resetTokens = null,
        )
        val report = adapter.buildReport(spend = null, limits = limits)

        assertNull(report.usedDollars)
        assertEquals(50, report.remainingPct) // 2500/5000 * 100 = 50
        assertEquals(1, report.windows.size) // only Requests/min
        assertEquals("Requests/min", report.windows[0].name)
    }

    @Test
    fun openAI_noData_nullRemaining() {
        val adapter = OpenAIProviderAdapter("openai-test", "fake-key", monthlyBudgetUsd = null)
        val report = adapter.buildReport(spend = null, limits = null)

        assertNull(report.remainingPct)
        assertFalse(report.available)
    }

    @Test
    fun openAI_fullBudgetSpend_zeroRemaining() {
        val adapter = OpenAIProviderAdapter("openai-test", "fake-key", monthlyBudgetUsd = 100.0)
        val report = adapter.buildReport(spend = 100.0, limits = null)

        assertEquals(0, report.remainingPct)
        assertEquals(1, report.windows.size)
        assertEquals(0, report.windows[0].remainingPct)
    }

    // -----------------------------------------------------------------------
    // Anthropic
    // -----------------------------------------------------------------------

    @Test
    fun anthropic_budgetAndRateLimit_multipleWindows() {
        val adapter = AnthropicProviderAdapter("anthropic-test", "fake-key", monthlyBudgetUsd = 100.0)
        val limits = AnthropicRateLimitData(
            limitRequests = 1000,
            remainingRequests = 800,
            resetRequests = "2026-08-23T12:00:00Z",
            limitTokens = 100000,
            remainingTokens = 90000,
            resetTokens = "2026-08-23T12:00:00Z",
        )
        val report = adapter.buildReport(spend = 30.0, limits = limits)

        assertEquals("anthropic-test", report.providerId)
        assertEquals("Anthropic", report.displayName)
        assertEquals(ProviderType.SPEND_BUDGET, report.type)
        assertEquals(70, report.remainingPct) // 100 - 30% = 70
        assertTrue(report.available)
        assertEquals(30.0, report.usedDollars)
        assertEquals(100.0, report.limitDollars)

        assertEquals(3, report.windows.size)
        assertEquals("Monthly Budget", report.windows[0].name)
        assertEquals(70, report.windows[0].remainingPct)
        assertEquals("Requests/min", report.windows[1].name)
        assertEquals(80, report.windows[1].remainingPct) // 800/1000 * 100 = 80
        assertEquals("Tokens/min", report.windows[2].name)
        assertEquals(90, report.windows[2].remainingPct) // 90000/100000 * 100 = 90
    }

    @Test
    fun anthropic_onlySpend_noRateLimit() {
        val adapter = AnthropicProviderAdapter("anthropic-test", "fake-key", monthlyBudgetUsd = 50.0)
        val report = adapter.buildReport(spend = 15.0, limits = null)

        assertEquals(70, report.remainingPct) // 100 - 30% = 70
        assertEquals(1, report.windows.size)
        assertEquals("Monthly Budget", report.windows[0].name)
    }

    @Test
    fun anthropic_noData_nullRemaining() {
        val adapter = AnthropicProviderAdapter("anthropic-test", "fake-key", monthlyBudgetUsd = null)
        val report = adapter.buildReport(spend = null, limits = null)

        assertNull(report.remainingPct)
        assertFalse(report.available)
    }

    // -----------------------------------------------------------------------
    // Letta Cloud
    // -----------------------------------------------------------------------

    @Test
    fun lettaCloud_categoricalBuckets_correctPercentages() {
        val adapter = LettaCloudProviderAdapter("letta-test", "fake-key")
        val quota = LettaQuotaResponse(
            lettaTier = LettaTierInfo(bucket = "medium", dailyBucket = "high"),
            quotaWindowEnd = "2026-08-23T16:00:00Z",
            dailyQuotaWindowEnd = "2026-08-24T00:00:00Z",
        )
        val report = adapter.buildReport(quota, billing = null)

        assertEquals("letta-test", report.providerId)
        assertEquals("Letta Cloud", report.displayName)
        assertEquals(ProviderType.WINDOW_CREDIT, report.type)
        // overall = min(medium=50%, high=75%) = 50%
        assertEquals(50, report.remainingPct)
        assertTrue(report.available)

        assertEquals(2, report.windows.size)
        assertEquals("4h Quota Window", report.windows[0].name)
        assertEquals(50, report.windows[0].remainingPct) // medium = 50%
        assertEquals("Daily Quota Window", report.windows[1].name)
        assertEquals(75, report.windows[1].remainingPct) // high = 75%
    }

    @Test
    fun lettaCloud_exactPercentageOverridesBuckets() {
        val adapter = LettaCloudProviderAdapter("letta-test", "fake-key")
        val quota = LettaQuotaResponse(
            lettaTier = LettaTierInfo(bucket = "high", dailyBucket = "full"),
            quotaWindowEnd = "2026-08-23T16:00:00Z",
            dailyQuotaWindowEnd = "2026-08-24T00:00:00Z",
        )
        val billing = LettaCloudProviderAdapter.BillingData(
            percentUsed = 60.0,
            used = 600,
            limit = 1000,
            totalCredits = 1200,
            isLow = false,
            billingPeriodEnd = "2026-09-01T00:00:00Z",
        )
        val report = adapter.buildReport(quota, billing)

        // exactRemaining = 100 - 60 = 40, overrides buckets
        assertEquals(40, report.remainingPct)
        assertEquals(40, report.windows[0].remainingPct)
        assertEquals(40, report.windows[1].remainingPct)
        assertEquals(600, report.creditsUsed)
        assertEquals(1000, report.creditsLimit)
        assertEquals(1200, report.creditsTotal)
        assertFalse(report.creditsLow)
    }

    @Test
    fun lettaCloud_emptyBucket_zeroRemaining() {
        val adapter = LettaCloudProviderAdapter("letta-test", "fake-key")
        val quota = LettaQuotaResponse(
            lettaTier = LettaTierInfo(bucket = "empty", dailyBucket = "empty"),
            quotaWindowEnd = "2026-08-23T16:00:00Z",
            dailyQuotaWindowEnd = "2026-08-24T00:00:00Z",
        )
        val report = adapter.buildReport(quota, billing = null)

        assertEquals(0, report.remainingPct)
    }

    @Test
    fun lettaCloud_lowFlagSet() {
        val adapter = LettaCloudProviderAdapter("letta-test", "fake-key")
        val quota = LettaQuotaResponse(
            lettaTier = LettaTierInfo(bucket = "medium", dailyBucket = "medium"),
            quotaWindowEnd = "2026-08-23T16:00:00Z",
            dailyQuotaWindowEnd = "2026-08-24T00:00:00Z",
        )
        val billing = LettaCloudProviderAdapter.BillingData(
            percentUsed = null,
            used = null,
            limit = null,
            totalCredits = null,
            isLow = true,
            billingPeriodEnd = null,
        )
        val report = adapter.buildReport(quota, billing)

        assertTrue(report.creditsLow)
        assertTrue(report.rawDisplay.contains("LOW"))
    }

    @Test
    fun lettaCloud_noTier_noWindows() {
        val adapter = LettaCloudProviderAdapter("letta-test", "fake-key")
        val quota = LettaQuotaResponse(
            lettaTier = null,
            quotaWindowEnd = null,
            dailyQuotaWindowEnd = null,
        )
        val report = adapter.buildReport(quota, billing = null)

        assertEquals(0, report.windows.size)
        // available is always true for Letta Cloud (pay-as-you-go)
        assertTrue(report.available)
    }

    // -----------------------------------------------------------------------
    // Groq
    // -----------------------------------------------------------------------

    @Test
    fun groq_rateLimitBothWindows_correctPercentages() {
        val adapter = GroqProviderAdapter("groq-test", "fake-key")
        val limits = GroqRateLimitData(
            limitRequests = 10000,
            remainingRequests = 7000,
            resetRequests = "24h",
            limitTokens = 50000,
            remainingTokens = 25000,
            resetTokens = "60s",
        )
        val report = adapter.buildReport(limits)

        assertEquals("groq-test", report.providerId)
        assertEquals("Groq", report.displayName)
        assertEquals(ProviderType.RATE_LIMIT, report.type)
        // overall = min(7000/10000=70%, 25000/50000=50%) = 50%
        assertEquals(50, report.remainingPct)
        assertTrue(report.available)

        assertEquals(2, report.windows.size)
        assertEquals("Requests/day", report.windows[0].name)
        assertEquals(70, report.windows[0].remainingPct)
        assertEquals("Tokens/min", report.windows[1].name)
        assertEquals(50, report.windows[1].remainingPct)
    }

    @Test
    fun groq_onlyRequestsWindow() {
        val adapter = GroqProviderAdapter("groq-test", "fake-key")
        val limits = GroqRateLimitData(
            limitRequests = 10000,
            remainingRequests = 5000,
            resetRequests = "24h",
            limitTokens = null,
            remainingTokens = null,
            resetTokens = null,
        )
        val report = adapter.buildReport(limits)

        assertEquals(50, report.remainingPct)
        assertEquals(1, report.windows.size)
        assertEquals("Requests/day", report.windows[0].name)
    }

    @Test
    fun groq_nullLimits_unavailable() {
        val adapter = GroqProviderAdapter("groq-test", "fake-key")
        val report = adapter.buildReport(null)

        assertFalse(report.available)
        assertEquals("Rate limit data unavailable", report.rawDisplay)
    }

    @Test
    fun groq_exhaustedRequests_zeroRemaining() {
        val adapter = GroqProviderAdapter("groq-test", "fake-key")
        val limits = GroqRateLimitData(
            limitRequests = 10000,
            remainingRequests = 0,
            resetRequests = "24h",
            limitTokens = 50000,
            remainingTokens = 50000,
            resetTokens = "60s",
        )
        val report = adapter.buildReport(limits)

        assertEquals(0, report.remainingPct) // min(0%, 100%) = 0
    }
}
