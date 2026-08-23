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
import com.angussoftware.fueldashboard.util.formatRoot

/**
 * Polls the Anthropic platform for billing spend and rate-limit status.
 *
 * **Two data sources:**
 *
 * 1. **Cost Report API** (SPEND_BUDGET): `GET /v1/organizations/cost_report`
 *    Returns daily cost buckets with costs in **cents** as decimal strings.
 *    - **Requires an admin key** (`sk-ant-admin...`) — standard API keys
 *      (`sk-ant-api...`) will get a 403 on this endpoint.
 *    - Auth header: `x-api-key: $ANTHROPIC_ADMIN_KEY` + `anthropic-version: 2023-06-01`
 *    - Amounts are in cents (e.g., `"123.45"` = $1.23). Daily granularity only.
 *
 * 2. **Rate-limit headers** (RATE_LIMIT): Anthropic includes rate-limit
 *    headers on API responses. We capture them from a **free** `GET /v1/models`
 *    call — never from inference endpoints. (Historical note: an earlier
 *    version probed `POST /v1/messages` with max_tokens=1 every poll, which
 *    made a billable inference call every 30 seconds — the exact harm a
 *    quota monitor exists to prevent. Removed; polls must never spend money.)
 *
 * **Note on key types**: Anthropic uses separate keys for admin APIs and inference.
 * An admin key can access cost reports but may not receive inference rate-limit
 * headers, and vice versa. The adapter tries both and shows whatever succeeds.
 *
 * **Graceful degradation**: If the cost API fails (non-admin key, network error),
 * the adapter still reports rate-limit data. If rate-limit headers are absent,
 * only cost data is shown.
 *
 * @param monthlyBudgetUsd Optional manually-set monthly spending cap. When set,
 *   the BudgetBar shows spend against this limit.
 */
class AnthropicProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val monthlyBudgetUsd: Double? = null,
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Anthropic"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    private val json = SharedHttpClient.json
    private val client = SharedHttpClient.client

    companion object {
        private const val COST_REPORT_PATH = "/v1/organizations/cost_report"
        private const val MODELS_PATH = "/v1/models"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        // Header names for rate limits
        private const val HDR_LIMIT_REQUESTS = "anthropic-ratelimit-requests-limit"
        private const val HDR_REMAINING_REQUESTS = "anthropic-ratelimit-requests-remaining"
        private const val HDR_RESET_REQUESTS = "anthropic-ratelimit-requests-reset"
        private const val HDR_LIMIT_TOKENS = "anthropic-ratelimit-tokens-limit"
        private const val HDR_REMAINING_TOKENS = "anthropic-ratelimit-tokens-remaining"
        private const val HDR_RESET_TOKENS = "anthropic-ratelimit-tokens-reset"
    }

    override suspend fun poll(): ProviderReport {
        // 1. Try to fetch monthly costs (may fail with non-admin key)
        val monthlySpend = fetchMonthlySpend()

        // 2. Fetch rate-limit headers via lightweight POST /v1/messages call
        val rateLimits = fetchRateLimits()

        // 3. Build report
        return buildReport(monthlySpend, rateLimits)
    }

    // -----------------------------------------------------------------------
    // Cost Report API
    // -----------------------------------------------------------------------

    /**
     * Fetches month-to-date spend by querying the /v1/organizations/cost_report endpoint.
     *
     * The endpoint returns daily cost buckets. We sum the `amount` field (in cents)
     * across all buckets from the 1st of the current month to now.
     *
     * Returns null if the API call fails (e.g., non-admin key, 403).
     */
    private suspend fun fetchMonthlySpend(): Double? {
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
        val startingAt = monthStart.toInstant(tz).toString()
        val endingAt = Instant.fromEpochMilliseconds(nowMs).toString()

        return try {
            val response: HttpResponse = client.get("$baseUrl$COST_REPORT_PATH") {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                parameter("starting_at", startingAt)
                parameter("ending_at", endingAt)
            }

            if (!response.status.isSuccess()) return null

            val body: JsonObject = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val data: JsonArray = body["data"] as? JsonArray ?: return null

            // Sum all amounts across all buckets (amounts are in cents as decimal strings)
            val totalCents = data.sumOf { bucket ->
                val results = (bucket as? JsonObject)?.get("results") as? JsonArray
                results?.sumOf { line ->
                    (line as? JsonObject)?.get("amount")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                } ?: 0.0
            }

            // Convert cents to dollars
            val totalDollars = totalCents / 100.0
            if (totalDollars > 0.0) totalDollars else null
        } catch (e: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Rate Limits
    // -----------------------------------------------------------------------

    /**
     * Fetches rate-limit data from a **free** `GET /v1/models` call.
     *
     * Anthropic includes rate-limit headers on API responses. Polling must
     * never spend money — no inference-endpoint probes — so we capture
     * headers from the models list instead. If Anthropic doesn't return
     * rate-limit headers there, rate-limit data is simply absent (graceful).
     *
     * Returns null if the call fails or headers are absent.
     */
    private suspend fun fetchRateLimits(): AnthropicRateLimitData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$MODELS_PATH") {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
            }

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

            AnthropicRateLimitData(
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

    internal fun buildReport(spend: Double?, limits: AnthropicRateLimitData?): ProviderReport {
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

        // Rate-limit windows (RATE_LIMIT)
        if (limits != null) {
            if (limits.limitRequests != null && limits.remainingRequests != null) {
                val reqRemainingPct = if (limits.limitRequests > 0) {
                    (limits.remainingRequests.toFloat() / limits.limitRequests * 100).roundToInt().coerceIn(0, 100)
                } else 100
                windows.add(
                    ReportWindow(
                        name = "Requests/min",
                        remainingPct = reqRemainingPct,
                        resetsAt = parseRfc3339ToEpochMs(limits.resetRequests),
                        windowHours = 1.0 / 60.0,
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
                        resetsAt = parseRfc3339ToEpochMs(limits.resetTokens),
                        windowHours = 1.0 / 60.0,
                    ),
                )
            }
        }

        // Overall remainingPct
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
                append(formatRoot("$%.2f", spend))
                if (monthlyBudgetUsd != null) append(formatRoot(" / $%.2f", monthlyBudgetUsd))
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
     * Parses Anthropic's rate-limit reset timestamps (RFC 3339 format).
     * Returns epoch-ms, or null if parsing fails.
     */
    private fun parseRfc3339ToEpochMs(timestamp: String?): Long? {
        if (timestamp.isNullOrBlank()) return null
        return try {
            Instant.parse(timestamp).toEpochMilliseconds()
        } catch (e: Exception) {
            null
        }
    }

    override fun close() = Unit
}

// -----------------------------------------------------------------------
// Internal data classes
// -----------------------------------------------------------------------

internal data class AnthropicRateLimitData(
    val limitRequests: Int?,
    val remainingRequests: Int?,
    val resetRequests: String?,
    val limitTokens: Int?,
    val remainingTokens: Int?,
    val resetTokens: String?,
)
