package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.model.ZaiQuotaResponse
import com.angussoftware.fueldashboard.util.epochMillis
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Polls the z.ai quota API directly — no orchestrator required.
 *
 * Endpoint: `https://api.z.ai/api/monitor/usage/quota/limit`
 * Auth: raw API key in the Authorization header (no "Bearer " prefix — z.ai expects the key directly).
 *
 * The response contains usage percentages for TOKENS_LIMIT and optionally SESSION_LIMIT.
 * The percentage is **used** (not remaining), so remaining = 100 - percentage.
 *
 * Reset is a sliding 5-hour window. The `nextResetTime` field (epoch ms, UTC) tells us
 * when the window resets. Window position is computed from elapsed time within the window.
 */
class ZaiProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.z.ai",
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "z.ai"
    override val providerType: ProviderType = ProviderType.WINDOW_CREDIT

    private val client = SharedHttpClient.client

    companion object {
        private const val QUOTA_PATH = "/api/monitor/usage/quota/limit"
        private const val WINDOW_HOURS = 5.0
        private const val WINDOW_MS = (5 * 60 * 60 * 1000).toLong() // 5 hours
        /** Session (weekly) quota window length — docs: "resets every 7 days". */
        private const val SESSION_WINDOW_HOURS = 168.0
    }

    override suspend fun poll(): ProviderReport {
        val endpoint = "$baseUrl$QUOTA_PATH"
        val response: ZaiQuotaResponse = client.get(endpoint) {
            header(HttpHeaders.Authorization, apiKey)
            header(HttpHeaders.AcceptLanguage, "en-US,en")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }.body()

        if (!response.success) {
            throw RuntimeException("z.ai API error: ${response.msg ?: "unknown error"}")
        }

        return mapToProviderReport(response)
    }

    /**
     * Maps the z.ai quota response to a [ProviderReport].
     *
     * The z.ai API tracks usage as "percentage used" — we convert to "percentage remaining"
     * for the dashboard's fuel-bar UI.
     */
    internal fun mapToProviderReport(response: ZaiQuotaResponse): ProviderReport {
        val limits = response.data.limits
        val tokensLimit = limits.firstOrNull { it.type == "TOKENS_LIMIT" }
        val sessionLimit = limits.firstOrNull { it.type == "SESSION_LIMIT" }

        val now = epochMillis()
        val windows = mutableListOf<ReportWindow>()

        // Parse token limit. When the API omits TOKENS_LIMIT the headline
        // must be UNKNOWN (null), not a fabricated 100% — "100% fuel" from a
        // missing field feeds the waste tiles, advisor regime, and alert
        // suppression with invented data (adversarial review H4).
        val tokensUsedPct: Int? = tokensLimit?.percentage
        val tokensRemaining: Int? = tokensUsedPct?.let { (100 - it).coerceIn(0, 100) }
        // Fallback: if API doesn't return nextResetTime, compute from window size
        val tokenResetTime = tokensLimit?.nextResetTime
        val resetMs = tokenResetTime ?: (now + WINDOW_MS)
        val tokenResetEstimated = tokenResetTime == null

        if (tokensLimit != null) {
            windows.add(
                ReportWindow(
                    name = "5h Token Window",
                    remainingPct = tokensRemaining ?: 100,
                    resetsAt = resetMs,
                    windowHours = WINDOW_HOURS,
                    resetEstimated = tokenResetEstimated,
                ),
            )
        }

        if (sessionLimit != null) {
            val sessionUsed = sessionLimit.percentage
            val sessionRemaining = (100 - sessionUsed).coerceIn(0, 100)
            val sessionResetTime = sessionLimit.nextResetTime
            // The session quota is the WEEKLY limit — "resets every 7 days"
            // (docs.z.ai/devpack/overview). The window LENGTH is a type
            // constant, NOT derivable from resetsAt (deriving it makes
            // total ≡ remaining → hourglass pinned at 100% all week; review
            // 1975). A resetsAt outside sane bounds is treated as absent.
            val sessionResetValid = sessionResetTime?.let { reset ->
                val ms = reset - now
                ms in 0..(45L * 24 * 3_600_000) // sane: 0..45 days out
            } == true
            windows.add(
                ReportWindow(
                    name = "Session",
                    remainingPct = sessionRemaining,
                    resetsAt = if (sessionResetValid) sessionResetTime else null,
                    windowHours = if (sessionResetValid) SESSION_WINDOW_HOURS else 0.0,
                    resetEstimated = !sessionResetValid,
                ),
            )
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = tokensRemaining,
            resetsAt = if (tokensLimit != null) resetMs else null,
            windowHours = if (tokensLimit != null) WINDOW_HOURS else 0.0,
            resetEstimated = tokenResetEstimated,
            available = windows.isNotEmpty(),
            windows = windows,
            rawDisplay = (tokensUsedPct?.let { "tokens:$it%" } ?: "tokens:—") +
                (sessionLimit?.let { " session:${it.percentage}%" } ?: ""),
        )
    }

    override fun close() = Unit
}
