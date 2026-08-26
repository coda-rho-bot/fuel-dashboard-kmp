package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture tests for the OpenRouter adapter.
 *
 * Wire fixtures follow the documented schema of `GET https://openrouter.ai/api/v1/key`
 * (regular API key auth): data.limit, data.limit_remaining, data.usage_daily,
 * data.usage_monthly, data.usage, data.is_free_tier. Nulls for unlimited keys.
 */
class OpenRouterAdapterTest {

    private val adapter = OpenRouterProviderAdapter("or-test", "fake-key")

    private fun wireBody(
        limit: Double? = 100.0,
        limitRemaining: Double? = 25.75,
        usageDaily: Double = 1.25,
        usageMonthly: Double = 74.25,
        usage: Double = 1200.5,
        isFreeTier: Boolean = false,
    ): String {
        val limitJson = if (limit == null) "null" else limit.toString()
        val limitRemJson = if (limitRemaining == null) "null" else limitRemaining.toString()
        return """
        {
          "data": {
            "label": "fuel-dashboard",
            "limit": $limitJson,
            "limit_remaining": $limitRemJson,
            "limit_reset": null,
            "usage": $usage,
            "usage_daily": $usageDaily,
            "usage_weekly": 30.5,
            "usage_monthly": $usageMonthly,
            "byok_usage": 0,
            "byok_usage_daily": 0,
            "byok_usage_weekly": 0,
            "byok_usage_monthly": 0,
            "is_free_tier": $isFreeTier,
            "is_management_key": false,
            "is_provisioning_key": false,
            "include_byok_in_limit": false,
            "rate_limit": -1
          }
        }
        """.trimIndent()
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    @Test
    fun parse_fullWireResponse_allFieldsExtracted() {
        val data = adapter.parseKeyResponse(wireBody())!!
        assertEquals(100.0, data.limit)
        assertEquals(25.75, data.limitRemaining)
        assertEquals(1.25, data.usageDaily)
        assertEquals(74.25, data.usageMonthly)
        assertEquals(1200.5, data.usageTotal)
        assertEquals(false, data.isFreeTier)
    }

    @Test
    fun parse_nullLimitFields_unlimitedKey() {
        val data = adapter.parseKeyResponse(wireBody(limit = null, limitRemaining = null))!!
        assertNull(data.limit)
        assertNull(data.limitRemaining)
    }

    @Test
    fun parse_garbageBody_returnsNull() {
        assertNull(adapter.parseKeyResponse("<html>error</html>"))
        assertNull(adapter.parseKeyResponse("{\"nope\": 1}"))
    }

    // -----------------------------------------------------------------------
    // Report semantics — per-key cap
    // -----------------------------------------------------------------------

    @Test
    fun keyCap_budgetDerivedFromLimitAndRemaining() {
        val data = adapter.parseKeyResponse(wireBody())!!
        val report = adapter.buildReport(data)

        assertEquals("or-test", report.providerId)
        assertEquals("OpenRouter", report.displayName)
        assertEquals(ProviderType.SPEND_BUDGET, report.type)
        assertTrue(report.available)
        // remaining 25.75 / limit 100 = 25.75% → rounds to 26
        assertEquals(26, report.remainingPct)
        // used = 100 - 25.75 = 74.25
        assertEquals(74.25, report.usedDollars)
        assertEquals(100.0, report.limitDollars)
        assertNull(report.resetsAt)
    }

    @Test
    fun keyCap_exhaustedRemaining_zeroPercent() {
        val data = adapter.parseKeyResponse(wireBody(limitRemaining = 0.0))!!
        val report = adapter.buildReport(data)
        assertEquals(0, report.remainingPct)
        assertEquals(100.0, report.usedDollars)
    }

    // -----------------------------------------------------------------------
    // Report semantics — no cap, user monthly budget fallback
    // -----------------------------------------------------------------------

    @Test
    fun noCap_monthlyBudgetFallback_usesApiMonthlyUsage() {
        val budgeted = OpenRouterProviderAdapter("or-test", "fake-key", monthlyBudgetUsd = 100.0)
        val data = budgeted.parseKeyResponse(wireBody(limit = null, limitRemaining = null, usageMonthly = 40.0))!!
        val report = budgeted.buildReport(data)

        assertEquals(60, report.remainingPct) // 100 - 40% used
        assertEquals(40.0, report.usedDollars)
        assertEquals(100.0, report.limitDollars)
    }

    @Test
    fun noCap_noBudget_spendOnlyNoGauge() {
        val data = adapter.parseKeyResponse(wireBody(limit = null, limitRemaining = null))!!
        val report = adapter.buildReport(data)

        assertNull(report.remainingPct)
        assertNull(report.usedDollars)
        assertNull(report.limitDollars)
        assertTrue(report.available)
        assertTrue(report.rawDisplay.contains("this month"))
    }

    // -----------------------------------------------------------------------
    // rawDisplay
    // -----------------------------------------------------------------------

    @Test
    fun rawDisplay_includesCapAndSpend() {
        val data = adapter.parseKeyResponse(wireBody())!!
        val report = adapter.buildReport(data)
        assertTrue(report.rawDisplay.contains("cap"))
        assertTrue(report.rawDisplay.contains("today"))
        assertTrue(report.rawDisplay.contains("this month"))
    }
}
