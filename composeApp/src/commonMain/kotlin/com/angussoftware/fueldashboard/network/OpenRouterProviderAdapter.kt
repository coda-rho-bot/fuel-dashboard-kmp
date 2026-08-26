package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * Polls the OpenRouter API for credit and usage status.
 *
 * **Single data source:**
 *
 * - **`GET /api/v1/key`**: works with a regular (inference) API key — no
 *   management key required. Returns per-key spending data:
 *   - `limit` — spending cap for the key in USD, or null if unlimited
 *   - `limit_remaining` — remaining cap in USD, or null if unlimited
 *   - `usage_daily` / `usage_monthly` — credits used (USD), current UTC
 *     day / month
 *   - `is_free_tier` — whether the account has ever purchased credits
 *
 *   (`GET /api/v1/credits` exists too, but requires a management key and
 *   only returns all-time totals — not useful for a regular user's gauge.)
 *
 * **Auth**: `Authorization: Bearer $OPENROUTER_API_KEY` (standard API key)
 *
 * **Fuel type**: SPEND_BUDGET (prepaid credits + optional per-key cap).
 *
 * **Report semantics:**
 * - Key cap set (`limit` != null): usedDollars = limit − limit_remaining,
 *   limitDollars = limit, remainingPct from the cap. The cap refills only
 *   if `limit_reset` is configured (we don't guess the reset time — OpenRouter
 *   returns a reset *type*, not a timestamp).
 * - No cap: falls back to a user-set monthly budget vs `usage_monthly`
 *   (same UX as the OpenAI/Anthropic/Mistral adapters). Without either,
 *   shows spend-only (no gauge).
 */
class OpenRouterProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://openrouter.ai/api",
    private val monthlyBudgetUsd: Double? = null,
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "OpenRouter"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    private val client = SharedHttpClient.client

    companion object {
        private const val KEY_PATH = "/v1/key"
    }

    override suspend fun poll(): ProviderReport {
        val keyData = fetchKeyData() ?: return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = null,
            available = false,
            rawDisplay = "Key data unavailable",
        )
        return buildReport(keyData)
    }

    // -----------------------------------------------------------------------
    // Data fetching
    // -----------------------------------------------------------------------

    private suspend fun fetchKeyData(): OpenRouterKeyData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$KEY_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (!response.status.isSuccess()) return null
            val body = response.bodyAsText()
            parseKeyResponse(body) ?: return null
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseKeyResponse(body: String): OpenRouterKeyData? {
        return try {
            val data = SharedHttpClient.json.parseToJsonElement(body)
                .jsonObject["data"]?.jsonObject ?: return null
            fun d(field: String): Double? =
                (data[field] as? JsonPrimitive)?.doubleOrNull
            OpenRouterKeyData(
                limit = d("limit"),
                limitRemaining = d("limit_remaining"),
                usageDaily = d("usage_daily"),
                usageMonthly = d("usage_monthly"),
                usageTotal = d("usage"),
                isFreeTier = (data["is_free_tier"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Report building
    // -----------------------------------------------------------------------

    internal fun buildReport(key: OpenRouterKeyData): ProviderReport {
        val hasKeyCap = key.limit != null && key.limit > 0 && key.limitRemaining != null

        // Primary: per-key spending cap. Secondary: user-set monthly budget
        // vs the API-reported monthly usage.
        val usedDollars: Double?
        val limitDollars: Double?
        val remainingPct: Int?

        when {
            hasKeyCap -> {
                val limit = key.limit!!
                val remaining = key.limitRemaining!!
                usedDollars = (limit - remaining).coerceAtLeast(0.0)
                limitDollars = limit
                remainingPct = if (limit > 0) {
                    (remaining / limit * 100).coerceIn(0.0, 100.0).roundToInt()
                } else null
            }
            monthlyBudgetUsd != null && monthlyBudgetUsd > 0 && key.usageMonthly != null -> {
                usedDollars = key.usageMonthly
                limitDollars = monthlyBudgetUsd
                val usedPct = (key.usageMonthly / monthlyBudgetUsd * 100.0).coerceIn(0.0, 100.0)
                remainingPct = (100 - usedPct.roundToInt()).coerceIn(0, 100)
            }
            else -> {
                usedDollars = null
                limitDollars = null
                remainingPct = null
            }
        }

        val rawDisplay = buildString {
            if (hasKeyCap) {
                append("$%.2f left of $%.2f cap".format(key.limitRemaining, key.limit))
            }
            if (key.usageDaily != null) {
                if (isNotEmpty()) append(" | ")
                append("$%.2f today".format(key.usageDaily))
            }
            if (key.usageMonthly != null) {
                if (isNotEmpty()) append(" | ")
                append("$%.2f this month".format(key.usageMonthly))
            }
            if (key.isFreeTier && isEmpty()) {
                append("Free tier")
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
            usedDollars = usedDollars,
            limitDollars = limitDollars,
            rawDisplay = rawDisplay,
        )
    }

    override fun close() = Unit
}

// -----------------------------------------------------------------------
// Internal data class
// -----------------------------------------------------------------------

internal data class OpenRouterKeyData(
    val limit: Double?,
    val limitRemaining: Double?,
    val usageDaily: Double?,
    val usageMonthly: Double?,
    val usageTotal: Double?,
    val isFreeTier: Boolean,
)
