package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.util.epochMillis
import com.angussoftware.fueldashboard.util.formatRoot
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Polls the Google Gemini API (AI Studio / generativelanguage surface) for
 * availability and rate-limit status.
 *
 * **What this surface exposes (and what it doesn't):**
 *
 * Gemini's rate limits (RPM/TPM/RPD per project, per model, per tier) are
 * visible in the AI Studio UI — there is NO quota or usage API for API keys,
 * and no billing/spend data on this surface. What we CAN do:
 *
 * - **`GET /v1beta/models`**: cheap, authenticated call — proves the key
 *   works (availability) and returns the model catalog (count shown in
 *   rawDisplay).
 * - **Rate-limit headers**: when present (`x-ratelimit-limit`,
 *   `x-ratelimit-remaining`, `x-ratelimit-reset`), we build a Requests/min
 *   window. Google emits these on some surfaces only — they are often absent
 *   on free-tier keys, in which case the report shows the model catalog
 *   without a gauge.
 *
 * **Auth**: `x-goog-api-key` header (equivalent to the documented `?key=`
 * query param, but keeps the key out of URLs and logs).
 *
 * **Fuel type**: RATE_LIMIT (throttle model — RPD resets at midnight
 * Pacific; RPM windows slide).
 */
class GeminiProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Google Gemini"
    override val providerType: ProviderType = ProviderType.RATE_LIMIT

    private val client = SharedHttpClient.client

    companion object {
        private const val MODELS_PATH = "/v1beta/models?pageSize=1000"
        private const val HDR_LIMIT = "x-ratelimit-limit"
        private const val HDR_REMAINING = "x-ratelimit-remaining"
        private const val HDR_RESET = "x-ratelimit-reset"
    }

    override suspend fun poll(): ProviderReport {
        val data = fetchModels() ?: return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = null,
            available = false,
            rawDisplay = "Model catalog unavailable — check API key",
        )
        return buildReport(data)
    }

    // -----------------------------------------------------------------------
    // Data fetching
    // -----------------------------------------------------------------------

    private suspend fun fetchModels(): GeminiModelsData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$MODELS_PATH") {
                header("x-goog-api-key", apiKey)
            }
            if (!response.status.isSuccess()) return null

            val limit = response.headers[HDR_LIMIT]?.toIntOrNull()
            val remaining = response.headers[HDR_REMAINING]?.toIntOrNull()
            val reset = response.headers[HDR_RESET]

            val modelCount = parseModelCount(response.bodyAsText())

            // Availability is proven by the successful call; headers are bonus.
            GeminiModelsData(
                modelCount = modelCount,
                limitRequests = limit?.takeIf { it > 0 },
                remainingRequests = remaining,
                resetRequests = reset,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Counts models in the paginated catalog response ({ models: [...] }). */
    internal fun parseModelCount(body: String): Int {
        return try {
            val root = SharedHttpClient.json.parseToJsonElement(body).jsonObject
            (root["models"]?.jsonArray ?: return 0).size
        } catch (_: Exception) {
            0
        }
    }

    // -----------------------------------------------------------------------
    // Report building
    // -----------------------------------------------------------------------

    internal fun buildReport(data: GeminiModelsData): ProviderReport {
        val windows = mutableListOf<ReportWindow>()
        var remainingPct: Int? = null

        if (data.limitRequests != null && data.remainingRequests != null) {
            val pct = if (data.limitRequests > 0) {
                (data.remainingRequests.toFloat() / data.limitRequests * 100).toInt().coerceIn(0, 100)
            } else 100
            remainingPct = pct
            windows.add(
                ReportWindow(
                    name = "Requests/min",
                    remainingPct = pct,
                    resetsAt = epochMillis() + parseResetToMs(data.resetRequests),
                    windowHours = 1.0 / 60.0,
                ),
            )
        }

        val rawDisplay = buildString {
            if (data.limitRequests != null && data.remainingRequests != null) {
                append(formatRoot("RPM:%d/%d", data.remainingRequests, data.limitRequests))
            }
            if (data.modelCount > 0) {
                if (isNotEmpty()) append(" | ")
                append("${data.modelCount} models")
            }
            if (isEmpty()) {
                append("Available · limits in AI Studio")
            }
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = remainingPct,
            resetsAt = null,
            windowHours = 0.0,
            available = true,
            windows = windows,
            rawDisplay = rawDisplay,
        )
    }

    /**
     * Parses reset values defensively: integer seconds ("59") or duration
     * strings ("1m30s" / "59.56s" — the format Google uses on some surfaces).
     */
    private fun parseResetToMs(reset: String?): Long {
        if (reset.isNullOrBlank()) return 60_000L
        reset.toLongOrNull()?.let { return it * 1000 }
        var totalMs = 0.0
        for (match in Regex("(\\d+(?:\\.\\d+)?)([smhd])").findAll(reset)) {
            val (value, unit) = match.destructured
            val n = value.toDoubleOrNull() ?: continue
            totalMs += when (unit) {
                "s" -> n * 1000
                "m" -> n * 60 * 1000
                "h" -> n * 3600 * 1000
                "d" -> n * 86400 * 1000
                else -> 0.0
            }
        }
        return if (totalMs > 0) totalMs.toLong() else 60_000L
    }

    override fun close() = Unit
}

// -----------------------------------------------------------------------
// Internal data class
// -----------------------------------------------------------------------

internal data class GeminiModelsData(
    val modelCount: Int,
    val limitRequests: Int?,
    val remainingRequests: Int?,
    val resetRequests: String?,
)
