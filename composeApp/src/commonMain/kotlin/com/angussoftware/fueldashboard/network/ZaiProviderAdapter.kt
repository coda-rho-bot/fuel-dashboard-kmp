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
    private fun mapToProviderReport(response: ZaiQuotaResponse): ProviderReport {
        val limits = response.data.limits
        val tokensLimit = limits.firstOrNull { it.type == "TOKENS_LIMIT" }
        val sessionLimit = limits.firstOrNull { it.type == "SESSION_LIMIT" }

        val now = epochMillis()
        val windows = mutableListOf<ReportWindow>()

        // Parse token limit
        val tokensUsedPct = tokensLimit?.percentage ?: 0
        val tokensRemaining = (100 - tokensUsedPct).coerceIn(0, 100)
        val resetMs = tokensLimit?.nextResetTime

        if (tokensLimit != null) {
            windows.add(
                ReportWindow(
                    name = "5h Token Window",
                    remainingPct = tokensRemaining,
                    resetsAt = resetMs,
                    windowHours = WINDOW_HOURS,
                ),
            )
        }

        if (sessionLimit != null) {
            val sessionUsed = sessionLimit.percentage
            val sessionRemaining = (100 - sessionUsed).coerceIn(0, 100)
            windows.add(
                ReportWindow(
                    name = "Session",
                    remainingPct = sessionRemaining,
                    resetsAt = sessionLimit.nextResetTime,
                    windowHours = WINDOW_HOURS,
                ),
            )
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = tokensRemaining,
            resetsAt = resetMs,
            windowHours = WINDOW_HOURS,
            available = windows.isNotEmpty(),
            windows = windows,
            rawDisplay = "tokens:${tokensUsedPct}%" +
                (sessionLimit?.let { " session:${it.percentage}%" } ?: ""),
        )
    }

    override fun close() = Unit
}
