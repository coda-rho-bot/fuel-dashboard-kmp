package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture tests for the xAI, Qwen (DashScope), and Together adapters.
 */
class ProviderBatchAdapterTest {

    // -----------------------------------------------------------------------
    // xAI
    // -----------------------------------------------------------------------

    private val xai = XaiProviderAdapter("xai-test", "fake-key")

    @Test
    fun xai_keyInfo_parsedAndDisplayed() {
        val info = xai.parseKeyResponse("""{"name": "my-grok-key", "status": "active"}""")!!
        assertEquals("my-grok-key", info.name)
        assertEquals("active", info.status)
        val report = xai.buildReport(info)
        assertTrue(report.available)
        assertNull(report.remainingPct) // honest: no gauge on this surface
        assertTrue(report.rawDisplay.contains("my-grok-key"))
        assertTrue(report.rawDisplay.contains("active"))
    }

    @Test
    fun xai_garbage_returnsNull() {
        assertNull(xai.parseKeyResponse("<html>"))
        // A valid JSON object with unrelated fields parses to null-field info
        // (not null) — key metadata is simply absent.
        val sparse = xai.parseKeyResponse("""{"unrelated": 1}""")!!
        assertNull(sparse.name)
        assertNull(sparse.status)
    }

    // -----------------------------------------------------------------------
    // Qwen (DashScope)
    // -----------------------------------------------------------------------

    private fun qwenWire(
        spendLimit: Double? = 50.0,
        monthlySpend: Double = 12.5,
        dailySpend: Double = 1.1,
        rpm: Int? = 60,
        tpm: Int? = 100000,
    ): String {
        val limitJson = spendLimit?.let { "\"spend_limit\": $it," } ?: ""
        val rpmJson = rpm?.let { "\"rpm\": $it," } ?: ""
        val tpmJson = tpm?.let { "\"tpm\": $it" } ?: ""
        return """{"data": {$limitJson "daily_spend": $dailySpend, "monthly_spend": $monthlySpend,
            "tokens_used": 2500000, "requests_used": 3400,
            "rate_limit": {$rpmJson $tpmJson}}}"""
    }

    @Test
    fun qwen_spendLimitGauge() {
        val adapter = QwenProviderAdapter("qwen-test", "fake-key")
        val data = adapter.parseQuotasResponse(qwenWire())!!
        val report = adapter.buildReport(data)

        assertEquals(ProviderType.SPEND_BUDGET, report.type)
        assertTrue(report.available)
        assertEquals(12.5, report.usedDollars)
        assertEquals(50.0, report.limitDollars)
        assertEquals(75, report.remainingPct) // 100 - 25%
    }

    @Test
    fun qwen_noSpendLimit_budgetFallback() {
        val adapter = QwenProviderAdapter("qwen-test", "fake-key", monthlyBudgetUsd = 100.0)
        val data = adapter.parseQuotasResponse(qwenWire(spendLimit = null, monthlySpend = 30.0))!!
        val report = adapter.buildReport(data)
        assertEquals(70, report.remainingPct)
        assertEquals(30.0, report.usedDollars)
        assertEquals(100.0, report.limitDollars)
    }

    @Test
    fun qwen_rawDisplay_hasTokensAndRequests() {
        val adapter = QwenProviderAdapter("qwen-test", "fake-key")
        val data = adapter.parseQuotasResponse(qwenWire())!!
        val report = adapter.buildReport(data)
        assertTrue(report.rawDisplay.contains("this month"))
        assertTrue(report.rawDisplay.contains("tokens"))
        assertTrue(report.rawDisplay.contains("req"))
    }

    // -----------------------------------------------------------------------
    // Together
    // -----------------------------------------------------------------------

    private fun togetherWire(): String {
        // Two rows this month, one last month — only current month sums.
        val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        val thisMonth = "%04d-%02d".format(now.year, now.monthValue)
        val lastMonthDate = now.minusMonths(1)
        val lastMonth = "%04d-%02d".format(lastMonthDate.year, lastMonthDate.monthValue)
        return """{"data": [
            {"date": "$thisMonth-03", "model_id": "meta-llama/Llama-4-Maverick", "input_tokens": 1000, "output_tokens": 2000, "total_cost": 1.25},
            {"date": "$thisMonth-15", "model_id": "Qwen/Qwen3-235B", "input_tokens": 500, "output_tokens": 500, "total_cost": 0.75},
            {"date": "${lastMonth}-20", "model_id": "old", "input_tokens": 9, "output_tokens": 9, "total_cost": 99.0}
        ]}"""
    }

    @Test
    fun together_sumsCurrentMonthOnly() {
        val adapter = TogetherProviderAdapter("t-test", "fake-key")
        assertEquals(2.0, adapter.sumMonthCost(togetherWire())!!) // 1.25 + 0.75, not 99
    }

    @Test
    fun together_budgetGauge() {
        val adapter = TogetherProviderAdapter("t-test", "fake-key", monthlyBudgetUsd = 10.0)
        val report = adapter.buildReport(2.0)
        assertEquals(80, report.remainingPct)
        assertEquals(2.0, report.usedDollars)
        assertEquals(10.0, report.limitDollars)
        assertTrue(report.rawDisplay.contains("balance in console"))
    }

    @Test
    fun together_noBudget_spendOnly() {
        val adapter = TogetherProviderAdapter("t-test", "fake-key")
        val report = adapter.buildReport(4.5)
        assertNull(report.remainingPct)
        assertNull(report.limitDollars)
        assertEquals(4.5, report.usedDollars)
    }

    @Test
    fun together_emptyMonth_returnsZero() {
        val adapter = TogetherProviderAdapter("t-test", "fake-key")
        // All rows dated 1999 — no current-month rows → 0.0 spend (found=false → 0.0)
        assertEquals(0.0, adapter.sumMonthCost("""{"data": [{"date": "1999-01-01", "total_cost": 5.0}]}""")!!)
    }

    @Test
    fun together_garbage_returnsNull() {
        val adapter = TogetherProviderAdapter("t-test", "fake-key")
        assertNull(adapter.sumMonthCost("<html>"))
    }
}
