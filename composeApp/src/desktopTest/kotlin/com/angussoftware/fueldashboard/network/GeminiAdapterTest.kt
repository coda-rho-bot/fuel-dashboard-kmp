package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture tests for the Google Gemini adapter.
 *
 * The generativelanguage surface has NO quota/usage API for keys — the adapter
 * proves key validity via GET /v1beta/models, counts the catalog, and builds a
 * Requests/min window ONLY when x-ratelimit-* headers are present (Google
 * emits them on some surfaces; often absent on free-tier keys).
 */
class GeminiAdapterTest {

    private val adapter = GeminiProviderAdapter("gem-test", "fake-key")

    /** Real shape of GET /v1beta/models (paginated catalog). */
    private fun wireModelsBody(count: Int = 3): String {
        val models = (1..count).joinToString(",\n") { n ->
            """{"name": "models/gemini-test-$n", "version": "00$n", "displayName": "Test $n",
                "inputTokenLimit": 1000000, "outputTokenLimit": 65536,
                "supportedGenerationMethods": ["generateContent"]}"""
        }
        return """{"models": [$models]}"""
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    @Test
    fun parse_modelCatalog_countsModels() {
        assertEquals(3, adapter.parseModelCount(wireModelsBody(3)))
        assertEquals(0, adapter.parseModelCount("""{"models": []}"""))
    }

    @Test
    fun parse_garbageBody_zeroModels() {
        assertEquals(0, adapter.parseModelCount("<html>error</html>"))
        assertEquals(0, adapter.parseModelCount("""{"unexpected": 1}"""))
    }

    // -----------------------------------------------------------------------
    // Report semantics — headers present
    // -----------------------------------------------------------------------

    @Test
    fun rateLimitHeaders_rpmWindowBuilt() {
        val data = GeminiModelsData(
            modelCount = 15,
            limitRequests = 10,
            remainingRequests = 8,
            resetRequests = "45",
        )
        val report = adapter.buildReport(data)

        assertEquals("gem-test", report.providerId)
        assertEquals("Google Gemini", report.displayName)
        assertEquals(ProviderType.RATE_LIMIT, report.type)
        assertTrue(report.available)
        assertEquals(80, report.remainingPct) // 8/10
        assertEquals(1, report.windows.size)
        assertEquals("Requests/min", report.windows[0].name)
        assertEquals(80, report.windows[0].remainingPct)
        assertTrue(report.rawDisplay.contains("RPM:8/10"))
        assertTrue(report.rawDisplay.contains("15 models"))
    }

    @Test
    fun exhaustedRpm_zeroPercent() {
        val data = GeminiModelsData(modelCount = 5, limitRequests = 10, remainingRequests = 0, resetRequests = null)
        val report = adapter.buildReport(data)
        assertEquals(0, report.remainingPct)
    }

    // -----------------------------------------------------------------------
    // Report semantics — headers absent (free tier common case)
    // -----------------------------------------------------------------------

    @Test
    fun noHeaders_catalogOnly_noGauge() {
        val data = GeminiModelsData(
            modelCount = 42,
            limitRequests = null,
            remainingRequests = null,
            resetRequests = null,
        )
        val report = adapter.buildReport(data)

        assertTrue(report.available)
        assertNull(report.remainingPct)
        assertEquals(0, report.windows.size)
        assertTrue(report.rawDisplay.contains("42 models"))
        assertFalse_rawDisplayNoRpm(report)
    }

    private fun assertFalse_rawDisplayNoRpm(report: com.angussoftware.fueldashboard.model.ProviderReport) {
        assertTrue(!report.rawDisplay.contains("RPM"), "rawDisplay should not contain RPM: ${report.rawDisplay}")
    }

    @Test
    fun durationStringReset_parsedDefensively() {
        // "1m30s" style — the parse helper lives privately; exercised via report
        val data = GeminiModelsData(modelCount = 1, limitRequests = 100, remainingRequests = 50, resetRequests = "1m30s")
        val report = adapter.buildReport(data)
        assertEquals(50, report.remainingPct)
        val resetsAt = report.windows[0].resetsAt
        assertTrue(resetsAt != null && resetsAt > System.currentTimeMillis(), "reset should be ~90s in future")
    }
}
