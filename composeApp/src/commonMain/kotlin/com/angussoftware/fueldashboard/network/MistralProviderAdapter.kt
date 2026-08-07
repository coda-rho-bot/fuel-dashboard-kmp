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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Polls the Mistral AI Admin API for spend, spend-limit, and rate-limit status.
 *
 * **Three endpoints (all under `/v1/admin`):**
 *
 * 1. **Usage API** (SPEND_BUDGET): `GET /v1/admin/usage`
 *    Returns monthly usage broken down by category (chat, completion, ocr, audio,
 *    connectors, fine_tuning) with per-model token counts and costs.
 *    - Each category contains a `models` array with nested usage/cost data.
 *    - The response includes `currency`, `start_date`, and `end_date`.
 *
 * 2. **Spend Limit API**: `GET /v1/admin/spend-limit`
 *    Returns the monthly spend limit status:
 *    - `limits.completion.monthly_limit_reached` (boolean)
 *    - `limits.currency` (e.g., "USD")
 *    - Note: This endpoint does **not** return the actual limit amount — only whether
 *      it has been reached. The user must set the monthly budget manually in settings.
 *
 * 3. **Rate Limit API** (RATE_LIMIT): `GET /v1/admin/rate-limit`
 *    Returns throughput limits:
 *    - `requests_per_second` (integer)
 *    - `tokens_limits_by_model` (array of objects with `tokens_per_minute` and `tokens_per_month`)
 *
 * **Graceful degradation**: If the usage API fails, the adapter still reports rate-limit
 * and spend-limit data. Each endpoint is polled independently.
 *
 * **Cost extraction**: The usage endpoint's `models` arrays contain deeply nested data
 * with both token counts (integers) and costs (decimal numbers). We recursively traverse
 * the structure and sum all decimal-valued numbers as approximate costs, since token
 * counts are always serialized as integers (no decimal point) and costs always include
 * fractional digits.
 *
 * Auth: `Authorization: Bearer <admin-api-key>` (also accepts `x-api-key` header)
 *
 * @param monthlyBudgetUsd Optional manually-set monthly spending cap (since the
 *   spend-limit endpoint only returns `monthly_limit_reached`, not the actual amount).
 *   When set, the BudgetBar shows spend against this limit.
 */
class MistralProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.mistral.ai",
    private val monthlyBudgetUsd: Double? = null,
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Mistral AI"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    private val json = SharedHttpClient.json
    private val client = SharedHttpClient.client

    companion object {
        private const val USAGE_PATH = "/v1/admin/usage"
        private const val SPEND_LIMIT_PATH = "/v1/admin/spend-limit"
        private const val RATE_LIMIT_PATH = "/v1/admin/rate-limit"

        /**
         * Categories in the usage response that contain per-model usage data.
         * Each has a `models` array with token counts and costs.
         */
        private val USAGE_CATEGORIES = listOf(
            "chat",
            "completion",
            "ocr",
            "audio",
            "connectors",
            "fine_tuning",
        )
    }

    override suspend fun poll(): ProviderReport {
        // 1. Fetch usage data (primary — for spend tracking)
        val usage = fetchUsage()

        // 2. Fetch spend-limit status (supplementary — budget cap status)
        val spendLimit = fetchSpendLimit()

        // 3. Fetch rate limits (supplementary — faucet display)
        val rateLimit = fetchRateLimit()

        // 4. Build report
        return buildReport(usage, spendLimit, rateLimit)
    }

    // -----------------------------------------------------------------------
    // Usage API
    // -----------------------------------------------------------------------

    /**
     * Fetches month-to-date spend from the /v1/admin/usage endpoint.
     *
     * Returns null if the API call fails.
     */
    private suspend fun fetchUsage(): UsageData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$USAGE_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            if (!response.status.isSuccess()) return null

            val body: JsonObject = json.parseToJsonElement(response.bodyAsText()).jsonObject

            val totalCost = USAGE_CATEGORIES.sumOf { category ->
                val catObj = body[category] as? JsonObject
                val modelsArray = catObj?.get("models") as? JsonArray
                if (modelsArray != null) extractCosts(modelsArray) else 0.0
            }

            val currency = body["currency"]?.jsonPrimitive?.contentOrNull
            val startDate = body["start_date"]?.jsonPrimitive?.contentOrNull
            val endDate = body["end_date"]?.jsonPrimitive?.contentOrNull

            UsageData(
                totalCost = totalCost,
                currency = currency,
                startDate = startDate,
                endDate = endDate,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Recursively traverses a JSON element and sums all decimal-valued numbers.
     *
     * Token counts are serialized as integers (e.g., `1000000`), while costs are
     * serialized as decimals (e.g., `2.50`). By checking for a decimal point in the
     * raw content, we reliably distinguish costs from token counts.
     */
    private fun extractCosts(element: JsonElement): Double {
        return when (element) {
            is JsonArray -> element.sumOf { extractCosts(it) }
            is JsonObject -> element.values.sumOf { extractCosts(it) }
            is JsonPrimitive -> {
                val content = element.contentOrNull ?: return 0.0
                // Only count decimal values — costs always have fractional digits,
                // while token counts are always integers.
                if (content.contains('.')) {
                    content.toDoubleOrNull() ?: 0.0
                } else {
                    0.0
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Spend Limit API
    // -----------------------------------------------------------------------

    /**
     * Fetches the spend-limit status from /v1/admin/spend-limit.
     *
     * The endpoint returns whether the monthly limit has been reached (boolean),
     * but NOT the actual limit amount.
     *
     * Returns null if the API call fails.
     */
    private suspend fun fetchSpendLimit(): SpendLimitData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$SPEND_LIMIT_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            if (!response.status.isSuccess()) return null

            val body: JsonObject = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val limits = body["limits"] as? JsonObject ?: return null

            val completionLimits = limits["completion"] as? JsonObject
            val monthlyLimitReached = completionLimits?.get("monthly_limit_reached")
                ?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

            val currency = limits["currency"]?.jsonPrimitive?.contentOrNull
            val lastPaymentFailure = limits["last_payment_failure"]
                ?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

            SpendLimitData(
                monthlyLimitReached = monthlyLimitReached,
                currency = currency,
                lastPaymentFailure = lastPaymentFailure,
            )
        } catch (e: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Rate Limit API
    // -----------------------------------------------------------------------

    /**
     * Fetches rate-limit data from /v1/admin/rate-limit.
     *
     * Returns requests-per-second and per-model token limits (TPM, TPMonth).
     *
     * Returns null if the API call fails.
     */
    private suspend fun fetchRateLimit(): MistralRateLimitData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$RATE_LIMIT_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            if (!response.status.isSuccess()) return null

            val body: JsonObject = json.parseToJsonElement(response.bodyAsText()).jsonObject

            val rps = body["requests_per_second"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

            // tokens_limits_by_model can be an array of objects or a map (model → limits)
            val maxTpm = extractMaxTpm(body["tokens_limits_by_model"])

            MistralRateLimitData(
                requestsPerSecond = rps,
                maxTokensPerMinute = maxTpm,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the maximum tokens_per_minute from the tokens_limits_by_model field.
     *
     * Handles both array and object representations.
     */
    private fun extractMaxTpm(element: JsonElement?): Int? {
        return when (element) {
            is JsonArray -> {
                element.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    obj["tokens_per_minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                }.maxOrNull()
            }
            is JsonObject -> {
                element.values.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    obj["tokens_per_minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                }.maxOrNull()
            }
            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // Report Building
    // -----------------------------------------------------------------------

    private fun buildReport(
        usage: UsageData?,
        spendLimit: SpendLimitData?,
        rateLimit: MistralRateLimitData?,
    ): ProviderReport {
        val windows = mutableListOf<ReportWindow>()

        // Budget window (SPEND_BUDGET)
        val spend = usage?.totalCost
        if (spend != null && spend > 0 && monthlyBudgetUsd != null && monthlyBudgetUsd > 0) {
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
        if (rateLimit != null) {
            if (rateLimit.requestsPerSecond != null && rateLimit.requestsPerSecond > 0) {
                windows.add(
                    ReportWindow(
                        name = "Requests/sec",
                        remainingPct = 100, // RPS is a throughput cap, not a depleting budget
                        resetsAt = epochMillis() + 1000,
                        windowHours = 1.0 / 3600.0, // ~1 second window
                    ),
                )
            }
            if (rateLimit.maxTokensPerMinute != null && rateLimit.maxTokensPerMinute > 0) {
                windows.add(
                    ReportWindow(
                        name = "Tokens/min Limit",
                        remainingPct = 100, // TPM is a throughput cap, not a depleting budget
                        resetsAt = epochMillis() + 60_000,
                        windowHours = 1.0 / 60.0, // ~1 minute window
                    ),
                )
            }
        }

        // Overall remainingPct: for SPEND_BUDGET, derive from budget if available
        val overallRemaining = when {
            spend != null && spend > 0 && monthlyBudgetUsd != null && monthlyBudgetUsd > 0 -> {
                val usedPct = (spend / monthlyBudgetUsd * 100.0).coerceIn(0.0, 100.0).roundToInt()
                (100 - usedPct).coerceIn(0, 100)
            }
            spendLimit?.monthlyLimitReached == true -> 0 // budget exhausted
            else -> null
        }

        val rawDisplay = buildString {
            if (spend != null && spend > 0) {
                append("$%.2f".format(spend))
                if (monthlyBudgetUsd != null) append(" / $%.2f".format(monthlyBudgetUsd))
                append(" MTD")
            }
            if (spendLimit?.monthlyLimitReached == true) {
                if (isNotEmpty()) append(" | ")
                append("LIMIT REACHED")
            }
            if (spendLimit?.lastPaymentFailure == true) {
                if (isNotEmpty()) append(" | ")
                append("PAYMENT ISSUE")
            }
            if (rateLimit != null) {
                if (rateLimit.requestsPerSecond != null && rateLimit.requestsPerSecond > 0) {
                    if (isNotEmpty()) append(" | ")
                    append("RPS:${rateLimit.requestsPerSecond}")
                }
                if (rateLimit.maxTokensPerMinute != null && rateLimit.maxTokensPerMinute > 0) {
                    if (isNotEmpty()) append(" | ")
                    append("TPM cap:${rateLimit.maxTokensPerMinute}")
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
            available = spend != null || rateLimit != null || spendLimit != null,
            windows = windows,
            usedDollars = spend,
            limitDollars = monthlyBudgetUsd,
            rawDisplay = rawDisplay,
        )
    }

    // -----------------------------------------------------------------------
    // Time helpers (ported from OpenAIProviderAdapter)
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

    override fun close() = Unit
}

// -----------------------------------------------------------------------
// Internal data classes
// -----------------------------------------------------------------------

private data class UsageData(
    val totalCost: Double,
    val currency: String?,
    val startDate: String?,
    val endDate: String?,
)

private data class SpendLimitData(
    val monthlyLimitReached: Boolean,
    val currency: String?,
    val lastPaymentFailure: Boolean,
)

private data class MistralRateLimitData(
    val requestsPerSecond: Int?,
    val maxTokensPerMinute: Int?,
)
