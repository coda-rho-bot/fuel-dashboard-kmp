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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Polls the OpenAI platform for billing spend and rate-limit status.
 *
 * **Two data sources:**
 *
 * 1. **Costs API** (SPEND_BUDGET): `GET /v1/organization/costs?start_time=<unix_sec>`
 *    Returns daily cost buckets for the current billing month.
 *    - **Requires an admin-scoped key** (`sk-admin-...`) with `api.usage.read` scope.
 *    - Standard `sk-...` keys will get 403 on this endpoint.
 *    - The old `/v1/dashboard/billing/subscription` endpoint that returned `hard_limit_usd`
 *      has been deprecated since 2024 — no API method exists to retrieve the monthly
 *      spending cap anymore. The user must set it manually in settings.
 *
 * 2. **Rate-limit headers** (RATE_LIMIT): Headers on any API call.
 *    We make a lightweight `GET /v1/models` call to capture:
 *    - `x-ratelimit-remaining-requests` / `x-ratelimit-limit-requests`
 *    - `x-ratelimit-remaining-tokens` / `x-ratelimit-limit-tokens`
 *    - `x-ratelimit-reset-requests` / `x-ratelimit-reset-tokens`
 *
 *    These are per-minute windows that reset continuously.
 *
 * **Graceful degradation**: If the costs API fails (non-admin key, network error),
 * the adapter still reports rate-limit data. If rate-limit headers are absent
 * (some endpoints/models don't return them), only cost data is shown.
 *
 * Auth: `Authorization: Bearer <api-key>`
 *
 * @param monthlyBudgetUsd Optional manually-set monthly spending cap (since OpenAI
 *   removed the API endpoint that returns `hard_limit_usd`). When set, the
 *   BudgetBar shows spend against this limit. When null, only spend is shown.
 */
class OpenAIProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val monthlyBudgetUsd: Double? = null,
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "OpenAI"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    private val json = SharedHttpClient.json
    private val client = SharedHttpClient.client

    companion object {
        private const val COSTS_PATH = "/v1/organization/costs"
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
        // 1. Try to fetch monthly costs (may fail with non-admin key)
        val monthlySpend = fetchMonthlySpend()

        // 2. Fetch rate-limit headers via lightweight /v1/models call
        val rateLimits = fetchRateLimits()

        // 3. Build report
        return buildReport(monthlySpend, rateLimits)
    }

    // -----------------------------------------------------------------------
    // Costs API
    // -----------------------------------------------------------------------

    /**
     * Fetches month-to-date spend by querying the /v1/organization/costs endpoint.
     *
     * The endpoint returns daily cost buckets. We sum the `price` field across
     * all buckets from the 1st of the current month to now.
     *
     * Returns null if the API call fails (e.g., non-admin key, 403).
     */
    private suspend fun fetchMonthlySpend(): Double? {
        val startEpochSec = monthStartEpochSeconds()
        val nowEpochSec = epochMillis() / 1000

        return try {
            val response: HttpResponse = client.get("$baseUrl$COSTS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                parameter("start_time", startEpochSec)
                parameter("end_time", nowEpochSec)
                parameter("bucket_width", "1d")
                parameter("limit", 31)
            }

            if (!response.status.isSuccess()) return null

            val body: JsonObject = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val data: JsonArray = body["data"] as? JsonArray ?: return null

            data.sumOf { bucket ->
                val priceObj = (bucket as? JsonObject)?.get("results") as? JsonArray
                priceObj?.sumOf { line ->
                    (line as? JsonObject)?.get("amount")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                } ?: 0.0
            }.let { if (it > 0.0) it else null }
        } catch (e: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Rate Limits
    // -----------------------------------------------------------------------

    /**
     * Fetches rate-limit data by making a lightweight GET /v1/models call.
     *
     * OpenAI includes rate-limit headers on all API responses. The /v1/models
     * endpoint is chosen because it's fast, free, and doesn't consume tokens.
     *
     * Returns null if the call fails or headers are absent.
     */
    private suspend fun fetchRateLimits(): RateLimitData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$MODELS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            val headers = response.headers

            val limitReq = headers[HDR_LIMIT_REQUESTS]?.toIntOrNull()
            val remainingReq = headers[HDR_REMAINING_REQUESTS]?.toIntOrNull()
            val resetReq = headers[HDR_RESET_REQUESTS]
            val limitTokens = headers[HDR_LIMIT_TOKENS]?.toIntOrNull()
            val remainingTokens = headers[HDR_REMAINING_TOKENS]?.toIntOrNull()
            val resetTokens = headers[HDR_RESET_TOKENS]

            // Only return if we got at least some useful data
            // (OpenAI sometimes returns -1 for these values on certain tiers/models)
            val hasRequestData = limitReq != null && limitReq > 0 && remainingReq != null
            val hasTokenData = limitTokens != null && limitTokens > 0 && remainingTokens != null

            if (!hasRequestData && !hasTokenData) return null

            RateLimitData(
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

    private fun buildReport(spend: Double?, limits: RateLimitData?): ProviderReport {
        val windows = mutableListOf<ReportWindow>()

        // Budget window (SPEND_BUDGET)
        if (spend != null && monthlyBudgetUsd != null && monthlyBudgetUsd > 0) {
            val usedPct = (spend / monthlyBudgetUsd * 100.0).coerceIn(0.0, 100.0).roundToInt()
            val remainingPct = (100 - usedPct).coerceIn(0, 100)
            windows.add(
                ReportWindow(
                    name = "Monthly Budget",
                    remainingPct = remainingPct,
                    resetsAt = nextMonthStartEpochMs(),
                    windowHours = 24.0 * daysUntilMonthEnd(),
                ),
            )
        }

        // Rate-limit windows (RATE_LIMIT) — encoded as windows for display
        if (limits != null) {
            if (limits.limitRequests != null && limits.remainingRequests != null) {
                val reqRemainingPct = if (limits.limitRequests > 0) {
                    (limits.remainingRequests.toFloat() / limits.limitRequests * 100).roundToInt().coerceIn(0, 100)
                } else 100
                windows.add(
                    ReportWindow(
                        name = "Requests/min",
                        remainingPct = reqRemainingPct,
                        resetsAt = epochMillis() + parseResetDurationToMs(limits.resetRequests),
                        windowHours = 1.0 / 60.0, // ~1 minute window
                    ),
                )
            }
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
        }

        // Overall remainingPct: for SPEND_BUDGET, derive from budget if available,
        // otherwise fall back to the most constrained rate-limit window.
        val overallRemaining = when {
            spend != null && monthlyBudgetUsd != null && monthlyBudgetUsd > 0 -> {
                val usedPct = (spend / monthlyBudgetUsd * 100.0).coerceIn(0.0, 100.0).roundToInt()
                (100 - usedPct).coerceIn(0, 100)
            }
            limits != null -> {
                val reqPct = if (limits.limitRequests != null && limits.remainingRequests != null && limits.limitRequests > 0) {
                    (limits.remainingRequests.toFloat() / limits.limitRequests * 100).roundToInt()
                } else 100
                val tokPct = if (limits.limitTokens != null && limits.remainingTokens != null && limits.limitTokens > 0) {
                    (limits.remainingTokens.toFloat() / limits.limitTokens * 100).roundToInt()
                } else 100
                minOf(reqPct, tokPct)
            }
            else -> null
        }

        val rawDisplay = buildString {
            if (spend != null) {
                append("$%.2f".format(spend))
                if (monthlyBudgetUsd != null) append(" / $%.2f".format(monthlyBudgetUsd))
                append(" MTD")
            }
            if (limits != null) {
                if (limits.remainingRequests != null && limits.limitRequests != null) {
                    if (isNotEmpty()) append(" | ")
                    append("RPM:${limits.remainingRequests}/${limits.limitRequests}")
                }
                if (limits.remainingTokens != null && limits.limitTokens != null) {
                    if (isNotEmpty()) append(" | ")
                    append("TPM:${limits.remainingTokens}/${limits.limitTokens}")
                }
            }
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = overallRemaining,
            resetsAt = null,
            windowHours = 0.0,
            available = spend != null || limits != null,
            windows = windows,
            usedDollars = spend,
            limitDollars = monthlyBudgetUsd,
            rawDisplay = rawDisplay,
        )
    }

    // -----------------------------------------------------------------------
    // Time helpers
    // -----------------------------------------------------------------------

    /** Returns the epoch-seconds timestamp for the 1st of the current month at 00:00 UTC. */
    private fun monthStartEpochSeconds(): Long {
        val nowMs = epochMillis()
        val tz = TimeZone.UTC
        val nowLocal = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
        val monthStart = kotlinx.datetime.LocalDateTime(
            year = nowLocal.year,
            month = nowLocal.month,
            day = 1,
            hour = 0,
            minute = 0,
            second = 0,
            nanosecond = 0,
        )
        return monthStart.toInstant(tz).epochSeconds
    }

    /** Returns epoch-ms for the start of the next month (UTC). */
    private fun nextMonthStartEpochMs(): Long {
        val nowMs = epochMillis()
        val tz = TimeZone.UTC
        val nowLocal = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
        val year = if (nowLocal.monthNumber == 12) nowLocal.year + 1 else nowLocal.year
        val month = if (nowLocal.monthNumber == 12) 1 else nowLocal.monthNumber + 1
        val nextStart = kotlinx.datetime.LocalDateTime(
            year = year,
            month = kotlinx.datetime.Month(month),
            day = 1,
            hour = 0,
            minute = 0,
            second = 0,
            nanosecond = 0,
        )
        return nextStart.toInstant(tz).toEpochMilliseconds()
    }

    /** Approximate days remaining in the current month. */
    private fun daysUntilMonthEnd(): Double {
        val nowMs = epochMillis()
        val nextMonth = nextMonthStartEpochMs()
        val diffMs = nextMonth - nowMs
        return (diffMs.toDouble() / (24.0 * 60 * 60 * 1000)).coerceAtLeast(1.0)
    }

    /**
     * Parses OpenAI's rate-limit reset duration strings (e.g., "6s", "1m", "1m30s").
     * Returns milliseconds.
     */
    private fun parseResetDurationToMs(duration: String?): Long {
        if (duration.isNullOrBlank()) return 60_000L // default 1 min
        var totalMs = 0L
        // Match patterns like "6s", "1m", "1m30s", "2h"
        val regex = Regex("(\\d+)([smhd])")
        for (match in regex.findAll(duration)) {
            val (value, unit) = match.destructured
            val n = value.toLongOrNull() ?: continue
            totalMs += when (unit) {
                "s" -> n * 1000
                "m" -> n * 60 * 1000
                "h" -> n * 60 * 60 * 1000
                "d" -> n * 24 * 60 * 60 * 1000
                else -> 0
            }
        }
        return if (totalMs > 0) totalMs else 60_000L
    }

    override fun close() = Unit
}

// -----------------------------------------------------------------------
// Internal data classes
// -----------------------------------------------------------------------

private data class RateLimitData(
    val limitRequests: Int?,
    val remainingRequests: Int?,
    val resetRequests: String?,
    val limitTokens: Int?,
    val remainingTokens: Int?,
    val resetTokens: String?,
)
