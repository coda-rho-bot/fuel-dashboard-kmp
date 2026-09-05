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
 * - **Rate-limit headers on chat completions**: empirically (Aug 2026, live
 *   key) Groq emits x-ratelimit-* on chat completion responses but NOT on
 *   GET /v1/models. We resolve a ping model (hourly TTL cache), then make a
 *   max_tokens=1 chat ping to capture:
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

    // Self-throttle state: the poll loop fires every 30s, but each network
    // fetch costs request quota. Cap at one fetch per minute; between fetches
    // the cached report is served.
    private var cachedReport: ProviderReport? = null
    private var lastFetchMs: Long = 0L

    // Ping-model cache: model ids churn on a day scale — resolve hourly.
    private var cachedPingModel: String? = null
    private var pingModelResolvedAtMs: Long = 0L

    companion object {
        private const val MODELS_PATH = "/v1/models"
        private const val CHAT_PATH = "/v1/chat/completions"

        // Preference order for the header-ping model: cheap, stable ids.
        private val PING_MODEL_PREFERENCES = listOf(
            "openai/gpt-oss-20b",
            "groq/compound-mini",
            "llama-3.1-8b-instant",
        )

        // One network fetch per 15 minutes. The fetch is a real chat
        // completion that decrements the very RPD quota this gauge displays
        // (and burns ~10-30 tokens), so at 60s the monitor alone cost
        // 1,440 requests/day — enough to exhaust free-tier RPD by itself.
        // RPD/TDS move slowly at monitoring granularity; users who accept
        // the cost can tighten per-provider pollIntervalSeconds.
        internal const val MIN_FETCH_INTERVAL_MS = 15 * 60_000L

        // Ping model re-resolved hourly.
        internal const val MODEL_CACHE_TTL_MS = 3_600_000L

        // Header names for rate limits
        private const val HDR_LIMIT_REQUESTS = "x-ratelimit-limit-requests"
        private const val HDR_REMAINING_REQUESTS = "x-ratelimit-remaining-requests"
        private const val HDR_RESET_REQUESTS = "x-ratelimit-reset-requests"
        private const val HDR_LIMIT_TOKENS = "x-ratelimit-limit-tokens"
        private const val HDR_REMAINING_TOKENS = "x-ratelimit-remaining-tokens"
        private const val HDR_RESET_TOKENS = "x-ratelimit-reset-tokens"
    }

    override suspend fun poll(): ProviderReport {
        val now = epochMillis()
        if (isCacheFresh(now)) return cachedReport!!
        val rateLimits = fetchRateLimits(now)
        val report = if (rateLimits != null) {
            buildReport(rateLimits)
        } else {
            ProviderReport(
                providerId = providerId,
                displayName = displayName,
                type = providerType,
                remainingPct = null,
                available = false,
                rawDisplay = "Rate limit data unavailable",
            )
        }
        cachedReport = report
        lastFetchMs = now
        return report
    }

    internal fun isCacheFresh(nowMs: Long): Boolean =
        cachedReport != null && nowMs - lastFetchMs < MIN_FETCH_INTERVAL_MS

    // -----------------------------------------------------------------------
    // Rate Limits
    // -----------------------------------------------------------------------

    /**
     * Fetches rate-limit data via a minimal chat completion.
     *
     * Empirical (Aug 2026, live key): Groq emits x-ratelimit-* headers on
     * chat completion responses but NOT on GET /v1/models — the old
     * models.list poll came back headerless and reported "Rate limit data
     * unavailable". We now list models (cheap, no quota cost) to pick an
     * available one, then make a max_tokens=1 ping to harvest the headers.
     *
     * Groq's headers:
     * - `x-ratelimit-limit-requests` / `x-ratelimit-remaining-requests`
     * - `x-ratelimit-limit-tokens` / `x-ratelimit-remaining-tokens` (TPM)
     * - Reset values are duration strings like "1m26.4s" or "577ms"
     *
     * Returns null if the calls fail or headers are absent.
     */
    private suspend fun fetchRateLimits(now: Long): GroqRateLimitData? {
        return try {
            val model = pingModel(now) ?: return null
            val response: HttpResponse = client.post("$baseUrl$CHAT_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"$model","messages":[{"role":"user","content":"hi"}],"max_tokens":1}""")
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

    /**
     * Lists models and picks one for the rate-limit ping: first match from
     * the preference list that the account can access, else the first
     * available. Model ids churn (llama-3.1-8b-instant vanished between
     * adapter versions), so availability is checked every poll.
     */
    private suspend fun pingModel(now: Long): String? {
        cachedPingModel?.let { cached ->
            if (now - pingModelResolvedAtMs < MODEL_CACHE_TTL_MS) return cached
        }
        val resolved = resolvePingModel() ?: return null
        cachedPingModel = resolved
        pingModelResolvedAtMs = now
        return resolved
    }

    private suspend fun resolvePingModel(): String? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$MODELS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (!response.status.isSuccess()) return null
            val ids = SharedHttpClient.json.parseToJsonElement(response.bodyAsText())
                .let { root ->
                    (root as? kotlinx.serialization.json.JsonObject)?.get("data") as? kotlinx.serialization.json.JsonArray
                }?.mapNotNull { entry ->
                    ((entry as? kotlinx.serialization.json.JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
                }.orEmpty()
            PING_MODEL_PREFERENCES.firstOrNull { it in ids } ?: ids.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Report Building
    // -----------------------------------------------------------------------

    internal fun buildReport(limits: GroqRateLimitData?): ProviderReport {
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

internal data class GroqRateLimitData(
    val limitRequests: Int?,
    val remainingRequests: Int?,
    val resetRequests: String?,
    val limitTokens: Int?,
    val remainingTokens: Int?,
    val resetTokens: String?,
)
