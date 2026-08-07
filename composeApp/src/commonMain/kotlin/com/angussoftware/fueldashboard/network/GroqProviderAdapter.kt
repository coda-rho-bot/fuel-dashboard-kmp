package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.util.epochMillis
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Polls the Groq API for rate-limit status via response headers.
 *
 * **Single data source:**
 *
 * - **Rate-limit headers**: Groq includes comprehensive rate-limit headers on
 *   every API response. We make a lightweight `GET /v1/models` call to capture:
 *   - `x-ratelimit-limit-requests` / `x-ratelimit-remaining-requests` (RPD — requests/day)
 *   - `x-ratelimit-limit-tokens` / `x-ratelimit-remaining-tokens` (TPM — tokens/min)
 *   - `x-ratelimit-reset-requests` / `x-ratelimit-reset-tokens` (duration strings)
 *
 *   These are always present on successful responses.
 *
 * **Auth**: `Authorization: Bearer $GROQ_API_KEY` (standard API key)
 *
 * **Fuel type**: RATE_LIMIT (faucet model). Groq enforces per-model RPM, RPD,
 * TPM, and TPD limits. The headers give us real-time visibility into the
 * requests/day and tokens/min windows.
 *
 * Groq also has monthly spend limits (dashboard-only, ~10-15 min delay) but
 * there is no API endpoint to query spend. This adapter focuses on rate-limit
 * data only.
 */
class GroqProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.groq.com/openai",
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Groq"
    override val providerType: ProviderType = ProviderType.RATE_LIMIT

    private val json = SharedHttpClient.json
    private val client = SharedHttpClient.client

    companion object {
        private const val MODELS_PATH = "/v1/models"

        // Header names for rate limits
        private const val HDR_LIMIT_REQUESTS = "x-ratelimit-limit-requests"
        private const val HDR_REMAINING_REQUESTS = "x-ratelimit-remaining-requests"
        private const val HDR_RESET_REQUESTS = "x-ratelimit-reset-requests"
        private const val HDR_LIMIT_TOKENS = "x-ratelimit-limit-tokens"
        private const val HDR_REMAINING_TOKENS = "x-ratelimit-remaining-tokens"
        private const val HDR_RESET_TOKENS = "x-ratelimit-reset-tokens"
    }

    override suspend fun poll(): ProviderReport {
        val rateLimits = fetchRateLimits()
        return buildReport(rateLimits)
    }

    // -----------------------------------------------------------------------
    // Rate Limits
    // -----------------------------------------------------------------------

    /**
     * Fetches rate-limit data by making a lightweight GET /v1/models call.
     *
     * Groq includes rate-limit headers on all API responses. The /v1/models
     * endpoint is chosen because it's fast, free, and doesn't consume tokens.
     *
     * Groq's headers:
     * - `x-ratelimit-limit-requests`: RPD (requests/day)
     * - `x-ratelimit-limit-tokens`: TPM (tokens/min)
     * - Reset values are duration strings like "2m59.56s" or "7.66s"
     *
     * Returns null if the call fails or headers are absent.
     */
    private suspend fun fetchRateLimits(): GroqRateLimitData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$MODELS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            if (!response.status.isSuccess()) return null

            val headers = response.headers

            val limitReq = headers[HDR_LIMIT_REQUESTS]?.toIntOrNull()
            val remainingReq = headers[HDR_REMAINING_REQUESTS]?.toIntOrNull()
            val resetReq = headers[HDR_RESET_REQUESTS]
            val limitTokens = headers[HDR_LIMIT_TOKENS]?.toIntOrNull()
            val remainingTokens = headers[HDR_REMAINING_TOKENS]?.toIntOrNull()
            val resetTokens = headers[HDR_RESET_TOKENS]

            // Only return if we got at least some useful data
            val hasRequestData = limitReq != null && limitReq > 0 && remainingReq != null
            val hasTokenData = limitTokens != null && limitTokens > 0 && remainingTokens != null

            if (!hasRequestData && !hasTokenData) return null

            GroqRateLimitData(
                limitRequests = limitReq?.takeIf { it > 0 },
                remainingRequests = remainingReq,
                resetRequests = resetReq,
                limitTokens = limitTokens?.takeIf { it > 0 },
                remainingTokens = remainingTokens,
                resetTokens = resetTokens,
            )
        } catch (e: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Report Building
    // -----------------------------------------------------------------------

    private fun buildReport(limits: GroqRateLimitData?): ProviderReport {
        if (limits == null) {
            return ProviderReport(
                providerId = providerId,
                displayName = displayName,
                type = providerType,
                remainingPct = null,
                available = false,
                rawDisplay = "Rate limit data unavailable",
            )
        }

        val windows = mutableListOf<ReportWindow>()

        // Requests/day window (RPD)
        if (limits.limitRequests != null && limits.remainingRequests != null) {
            val reqRemainingPct = if (limits.limitRequests > 0) {
                (limits.remainingRequests.toFloat() / limits.limitRequests * 100).roundToInt().coerceIn(0, 100)
            } else 100
            windows.add(
                ReportWindow(
                    name = "Requests/day",
                    remainingPct = reqRemainingPct,
                    resetsAt = epochMillis() + parseResetDurationToMs(limits.resetRequests),
                    windowHours = 24.0, // daily window
                ),
            )
        }

        // Tokens/min window (TPM)
        if (limits.limitTokens != null && limits.remainingTokens != null) {
            val tokRemainingPct = if (limits.limitTokens > 0) {
                (limits.remainingTokens.toFloat() / limits.limitTokens * 100).roundToInt().coerceIn(0, 100)
            } else 100
            windows.add(
                ReportWindow(
                    name = "Tokens/min",
                    remainingPct = tokRemainingPct,
                    resetsAt = epochMillis() + parseResetDurationToMs(limits.resetTokens),
                    windowHours = 1.0 / 60.0,
                ),
            )
        }

        // Overall remainingPct: most constrained window
        val overallRemaining = run {
            val reqPct = if (limits.limitRequests != null && limits.remainingRequests != null && limits.limitRequests > 0) {
                (limits.remainingRequests.toFloat() / limits.limitRequests * 100).roundToInt()
            } else 100
            val tokPct = if (limits.limitTokens != null && limits.remainingTokens != null && limits.limitTokens > 0) {
                (limits.remainingTokens.toFloat() / limits.limitTokens * 100).roundToInt()
            } else 100
            minOf(reqPct, tokPct)
        }

        val rawDisplay = buildString {
            if (limits.remainingRequests != null && limits.limitRequests != null) {
                append("RPD:${limits.remainingRequests}/${limits.limitRequests}")
            }
            if (limits.remainingTokens != null && limits.limitTokens != null) {
                if (isNotEmpty()) append(" | ")
                append("TPM:${limits.remainingTokens}/${limits.limitTokens}")
            }
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = overallRemaining,
            resetsAt = null,
            windowHours = 0.0,
            available = true,
            windows = windows,
            rawDisplay = rawDisplay,
        )
    }

    // -----------------------------------------------------------------------
    // Time helpers
    // -----------------------------------------------------------------------

    /**
     * Parses Groq's rate-limit reset duration strings (e.g., "2m59.56s", "7.66s").
     * Supports fractional seconds. Returns milliseconds.
     */
    private fun parseResetDurationToMs(duration: String?): Long {
        if (duration.isNullOrBlank()) return 60_000L // default 1 min
        var totalMs = 0.0
        // Match patterns like "6s", "1m", "1m30.5s", "2h", "59.56s"
        val regex = Regex("(\\d+(?:\\.\\d+)?)([smhd])")
        for (match in regex.findAll(duration)) {
            val (value, unit) = match.destructured
            val n = value.toDoubleOrNull() ?: continue
            totalMs += when (unit) {
                "s" -> n * 1000
                "m" -> n * 60 * 1000
                "h" -> n * 60 * 60 * 1000
                "d" -> n * 24 * 60 * 60 * 1000
                else -> 0.0
            }
        }
        return if (totalMs > 0) totalMs.toLong() else 60_000L
    }

    override fun close() = Unit
}

// -----------------------------------------------------------------------
// Internal data classes
// -----------------------------------------------------------------------

private data class GroqRateLimitData(
    val limitRequests: Int?,
    val remainingRequests: Int?,
    val resetRequests: String?,
    val limitTokens: Int?,
    val remainingTokens: Int?,
    val resetTokens: String?,
)
