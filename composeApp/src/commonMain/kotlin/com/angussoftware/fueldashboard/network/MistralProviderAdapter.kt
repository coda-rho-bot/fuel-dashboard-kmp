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
import com.angussoftware.fueldashboard.util.formatRoot

/**
 * Polls the Mistral AI Admin API for spend, spend-limit, and rate-limit status.
 *
 * **Three endpoints (all under `/v1/admin`):**
 *
 * 1. **Usage API** (SPEND_BUDGET): `GET /v1/admin/usage`
 *    Returns monthly usage broken down by category (chat, completion, ocr, audio,
 *    connectors, fine_tuning, libraries_api, vibe_code) with per-model token counts.
 *    - Each category contains a `models` object mapping model names to usage entries.
 *    - Usage entries have `input`/`output`/`cached` arrays of billing records.
 *    - A `prices` array provides per-unit pricing keyed by `billing_metric::billing_group`.
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
 * **Cost extraction**: The usage endpoint returns a `prices` array (each entry has
 * `billing_metric`, `billing_group`, and `price` as a string) and per-category usage
 * data. Each category (chat, completion, ocr, etc.) has a `models` object mapping model
 * names to usage entries. Each usage entry has `input`, `output`, and/or `cached` arrays
 * of token-count records. Each record carries `value_paid` (or `value`) as the token
 * count, plus `billing_metric` and `billing_group` for price lookup.
 *
 * We build a price index from the `prices` array, then recursively traverse all
 * categories, multiplying each token count by its looked-up price
 * (`{billing_metric}::{billing_group}`) to compute total spend.
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
        private const val MODELS_PATH = "/v1/models"
        private const val CHAT_PATH = "/v1/chat/completions"

        // Regular keys (console.mistral.ai) get 401 on /v1/admin/* — admin
        // endpoints need a Backoffice admin key. Fallback: harvest rate
        // headers from a chat ping. Self-throttled like the Groq adapter —
        // 15 min, not 60s: the ping is a real completion that burns tokens
        // and decrements the monitored RPM window, so at 60s the monitor
        // alone cost 1,440 completions/day.
        internal const val MIN_FETCH_INTERVAL_MS = 15 * 60_000L

        /**
         * Keys in the usage response that are arrays of usage entries (token counts)
         * rather than structural objects. When traversing the JSON tree, we look for
         * these keys to identify arrays of billing records.
         */
        private val USAGE_ENTRY_KEYS = setOf("input", "output", "cached")

        /**
         * Top-level keys in the usage response that are NOT usage categories and
         * should be skipped during cost accumulation.
         */
        private val NON_CATEGORY_KEYS = setOf(
            "prices",
            "currency",
            "currency_symbol",
            "date",
            "start_date",
            "end_date",
            "next_month",
            "previous_month",
            "vibe_usage", // legacy field, always 0
        )
    }

    // Self-throttle state (see GroqProviderAdapter — same pattern).
    private var cachedFallbackReport: ProviderReport? = null
    private var lastFallbackFetchMs: Long = 0L

    // Definitive auth rejection on /v1/admin/* (401/403): a regular
    // console key — retrying the admin endpoints every poll is a guaranteed
    // 1,440 wasted requests/day. Set ONLY on 401/403 (a transient 5xx does
    // NOT set it — the admin path stays armed in case an admin key appears).
    @Volatile
    private var adminEndpointsBlocked = false

    // Ping-model cache (hourly, same as Groq): the fallback re-listed all
    // models on every poll just to pick a ping model.
    private var cachedPingModel: String? = null
    private var pingModelResolvedAtMs: Long = 0L

    /** Outcome of one admin-endpoint probe, distinguishing auth rejection. */
    private sealed interface AdminProbe {
        data class Available(val usage: UsageData) : AdminProbe
        data object AuthRejected : AdminProbe
        data object Unavailable : AdminProbe
    }

    override suspend fun poll(): ProviderReport {
        // 1. Fetch usage data (primary — for spend tracking). Skip the probe
        //    entirely once we know this key can't reach admin endpoints.
        if (!adminEndpointsBlocked) {
            when (val probe = probeUsage()) {
                is AdminProbe.Available -> {
                    // 2. Fetch spend-limit status (supplementary — budget cap)
                    val spendLimit = fetchSpendLimit()
                    // 3. Fetch rate limits (supplementary — faucet display)
                    val rateLimit = fetchRateLimit()
                    // 4. Build report
                    return buildReport(probe.usage, spendLimit, rateLimit)
                }
                AdminProbe.AuthRejected -> {
                    adminEndpointsBlocked = true
                    return fallbackPoll()
                }
                AdminProbe.Unavailable -> return fallbackPoll()
            }
        }
        return fallbackPoll()
    }

    /**
     * Probes /v1/admin/usage once, classifying the outcome. Auth rejection
     * (401/403) is definitive for this key; other failures are transient.
     */
    private suspend fun probeUsage(): AdminProbe {
        return try {
            val response: HttpResponse = client.get("$baseUrl$USAGE_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            when {
                response.status.value == 401 || response.status.value == 403 ->
                    AdminProbe.AuthRejected
                !response.status.isSuccess() -> AdminProbe.Unavailable
                else -> {
                    val bodyText = response.bodyAsText()
                    parseUsageData(bodyText)?.let { AdminProbe.Available(it) } ?: AdminProbe.Unavailable
                }
            }
        } catch (_: Exception) {
            AdminProbe.Unavailable
        }
    }

    // -----------------------------------------------------------------------
    // Regular-key fallback (rate headers via chat ping)
    // -----------------------------------------------------------------------

    private suspend fun fallbackPoll(): ProviderReport {
        val now = com.angussoftware.fueldashboard.util.epochMillis()
        if (cachedFallbackReport != null && now - lastFallbackFetchMs < MIN_FETCH_INTERVAL_MS) {
            return cachedFallbackReport!!
        }
        val report = fetchRateHeaderFallback() ?: ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = null,
            available = false,
            rawDisplay = "No data — admin key required for spend, chat ping failed",
        )
        cachedFallbackReport = report
        lastFallbackFetchMs = now
        return report
    }

    /**
     * Minimal chat completion to harvest Mistral's rate headers (regular
     * keys): x-ratelimit-limit-req-minute / x-ratelimit-remaining-req-minute
     * and x-ratelimit-limit-tokens-minute / x-ratelimit-remaining-tokens-minute.
     */
    private suspend fun fetchRateHeaderFallback(): ProviderReport? {
        return try {
            val model = pingModel() ?: return null

            val response: HttpResponse = client.post("$baseUrl$CHAT_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"$model","messages":[{"role":"user","content":"hi"}],"max_tokens":1}""")
            }
            if (!response.status.isSuccess()) return null
            val h = response.headers

            val limitReq = h["x-ratelimit-limit-req-minute"]?.toIntOrNull()
            val remainingReq = h["x-ratelimit-remaining-req-minute"]?.toIntOrNull()
            val limitTok = h["x-ratelimit-limit-tokens-minute"]?.toIntOrNull()
            val remainingTok = h["x-ratelimit-remaining-tokens-minute"]?.toIntOrNull()

            val windows = mutableListOf<ReportWindow>()
            if (limitReq != null && limitReq > 0 && remainingReq != null) {
                windows.add(ReportWindow(name = "Requests/min", remainingPct = (remainingReq * 100 / limitReq).coerceIn(0, 100), resetsAt = null, windowHours = 1.0 / 60.0))
            }
            if (limitTok != null && limitTok > 0 && remainingTok != null) {
                windows.add(ReportWindow(name = "Tokens/min", remainingPct = (remainingTok * 100 / limitTok).coerceIn(0, 100), resetsAt = null, windowHours = 1.0 / 60.0))
            }
            if (windows.isEmpty()) return null

            ProviderReport(
                providerId = providerId,
                displayName = displayName,
                type = providerType,
                remainingPct = windows.minOf { it.remainingPct ?: 100 },
                resetsAt = null,
                windowHours = 0.0,
                available = true,
                windows = windows,
                rawDisplay = "Regular key — rate limits only (spend needs admin key)",
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the ping model for the fallback chat header-harvest, cached
     * hourly (model ids churn slowly; the old code re-listed all models on
     * every poll — 1,440 catalog downloads/day for one id).
     */
    private suspend fun pingModel(): String? {
        val now = com.angussoftware.fueldashboard.util.epochMillis()
        cachedPingModel?.let { cached ->
            if (now - pingModelResolvedAtMs < 3_600_000L) return cached
        }
        val resolved = try {
            val modelsResponse: HttpResponse = client.get("$baseUrl$MODELS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (!modelsResponse.status.isSuccess()) return null
            SharedHttpClient.json.parseToJsonElement(modelsResponse.bodyAsText())
                .let { (it as? JsonObject)?.get("data") as? kotlinx.serialization.json.JsonArray }
                ?.mapNotNull { m -> ((m as? JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.firstOrNull { it.startsWith("mistral-") && !it.contains("embed") && !it.contains("ocr") }
        } catch (_: Exception) {
            null
        } ?: return null
        cachedPingModel = resolved
        pingModelResolvedAtMs = now
        return resolved
    }

    // -----------------------------------------------------------------------
    // Usage API
    // -----------------------------------------------------------------------

    /**
     * Parses the /v1/admin/usage response body into [UsageData]. Pure —
     * HTTP and status classification live in [probeUsage]. Returns null on
     * unparseable bodies.
     */
    private fun parseUsageData(bodyText: String): UsageData? {
        return try {
            val body: JsonObject = json.parseToJsonElement(bodyText).jsonObject

            // Build price index from the `prices` array.
            val priceIndex = buildPriceIndex(body)

            // Accumulate cost across all top-level keys (skip non-category keys).
            var totalCost = 0.0
            for ((key, value) in body) {
                if (key !in NON_CATEGORY_KEYS) {
                    accumulateCost(value, priceIndex) { totalCost += it }
                }
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
     * Builds a price index from the `prices` array in the usage response.
     *
     * Each price entry has `billing_metric`, `billing_group`, and `price` (as a string).
     * The index key is `"{billing_metric}::{billing_group}"`.
     */
    private fun buildPriceIndex(body: JsonObject): Map<String, Double> {
        val index = mutableMapOf<String, Double>()
        val pricesArray = body["prices"] as? JsonArray ?: return index
        for (priceEntry in pricesArray) {
            val obj = priceEntry as? JsonObject ?: continue
            val metric = obj["billing_metric"]?.jsonPrimitive?.contentOrNull
            val group = obj["billing_group"]?.jsonPrimitive?.contentOrNull
            val priceStr = obj["price"]?.jsonPrimitive?.contentOrNull
            if (metric != null && group != null && priceStr != null) {
                priceStr.toDoubleOrNull()?.let { price ->
                    index["$metric::$group"] = price
                }
            }
        }
        return index
    }

    /**
     * Recursively traverses the JSON tree, accumulating cost from usage entries.
     *
     * When a key is "input", "output", or "cached" and its value is an array, each
     * element is treated as a billing record with:
     * - `value_paid` or `value` (token count as integer)
     * - `billing_metric` and `billing_group` (for price lookup)
     *
     * The cost for each record is `tokenCount * price(metric, group)`.
     */
    private fun accumulateCost(element: JsonElement, priceIndex: Map<String, Double>, add: (Double) -> Unit) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key in USAGE_ENTRY_KEYS && value is JsonArray) {
                        for (entry in value) {
                            val entryObj = entry as? JsonObject ?: continue
                            val units = entryObj["value_paid"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                ?: entryObj["value"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                ?: 0L
                            val metric = entryObj["billing_metric"]?.jsonPrimitive?.contentOrNull
                            val group = entryObj["billing_group"]?.jsonPrimitive?.contentOrNull
                            if (metric != null && group != null) {
                                val priceKey = "$metric::$group"
                                priceIndex[priceKey]?.let { price ->
                                    add(units.toDouble() * price)
                                }
                            }
                        }
                    } else {
                        accumulateCost(value, priceIndex, add)
                    }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    accumulateCost(item, priceIndex, add)
                }
            }
            else -> {}
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

    internal fun buildReport(
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
                        resetEstimated = true,
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
                        resetEstimated = true,
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
                append(formatRoot("$%.2f", spend))
                if (monthlyBudgetUsd != null) append(formatRoot(" / $%.2f", monthlyBudgetUsd))
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

internal data class UsageData(
    val totalCost: Double,
    val currency: String?,
    val startDate: String?,
    val endDate: String?,
)

internal data class SpendLimitData(
    val monthlyLimitReached: Boolean,
    val currency: String?,
    val lastPaymentFailure: Boolean,
)

internal data class MistralRateLimitData(
    val requestsPerSecond: Int?,
    val maxTokensPerMinute: Int?,
)
