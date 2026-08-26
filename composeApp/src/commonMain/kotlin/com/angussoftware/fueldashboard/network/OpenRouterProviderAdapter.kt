package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.util.formatRoot
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

        // Contrary to the docs ("management key required"), /v1/credits
        // returns data for regular inference keys too (live-verified Aug
        // 2026): { data: { total_credits, total_usage } } — lifetime
        // purchases and lifetime consumption; balance is the difference.
        private const val CREDITS_PATH = "/v1/credits"
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
        val credits = fetchCredits()
        return buildReport(keyData, credits)
    }

    /** Lifetime purchased/used credits; null when the endpoint misbehaves. */
    private suspend fun fetchCredits(): OpenRouterCredits? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$CREDITS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (!response.status.isSuccess()) return null
            parseCreditsResponse(response.bodyAsText())
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseCreditsResponse(body: String): OpenRouterCredits? {
        return try {
            val data = SharedHttpClient.json.parseToJsonElement(body)
                .jsonObject["data"]?.jsonObject ?: return null
            val total = (data["total_credits"] as? JsonPrimitive)?.doubleOrNull
            val used = (data["total_usage"] as? JsonPrimitive)?.doubleOrNull
            if (total == null || used == null) null else OpenRouterCredits(total, used)
        } catch (_: Exception) {
            null
        }
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

    internal fun buildReport(
        key: OpenRouterKeyData,
        credits: OpenRouterCredits? = null,
    ): ProviderReport {
        // Capture locals so the when-branch null checks smart-cast them.
        val capLimit = key.limit?.takeIf { it > 0 }
        val capRemaining = key.limitRemaining

        // Primary: per-key spending cap. Secondary: user-set monthly budget
        // vs the API-reported monthly usage.
        val usedDollars: Double?
        val limitDollars: Double?
        val remainingPct: Int?

        fun hasKeyCap(): Boolean = capLimit != null && capRemaining != null

        val budgetBranch = monthlyBudgetUsd != null && monthlyBudgetUsd > 0 && key.usageMonthly != null &&
            capLimit == null

        when {
            capLimit != null && capRemaining != null -> {
                usedDollars = (capLimit - capRemaining).coerceAtLeast(0.0)
                limitDollars = capLimit
                remainingPct = (capRemaining / capLimit * 100).coerceIn(0.0, 100.0).roundToInt()
            }
            monthlyBudgetUsd != null && monthlyBudgetUsd > 0 && key.usageMonthly != null -> {
                usedDollars = key.usageMonthly
                limitDollars = monthlyBudgetUsd
                val usedPct = (key.usageMonthly / monthlyBudgetUsd * 100.0).coerceIn(0.0, 100.0)
                remainingPct = (100 - usedPct.roundToInt()).coerceIn(0, 100)
            }
            else -> {
                if (credits != null) {
                    // No key cap: the account balance (lifetime credits minus
                    // lifetime usage) becomes the prepaid-pool display. May be
                    // negative — OpenRouter allows overdraft until 402s.
                    usedDollars = null
                    limitDollars = credits.totalCredits - credits.totalUsage
                    remainingPct = null
                } else {
                    usedDollars = null
                    limitDollars = null
                    remainingPct = null
                }
            }
        }

        var rawDisplay = buildString {
            if (capLimit != null && capRemaining != null) {
                append(formatRoot("$%.2f left of $%.2f cap", capRemaining, capLimit))
            }
            if (key.usageDaily != null) {
                if (isNotEmpty()) append(" | ")
                append(formatRoot("$%.2f today", key.usageDaily))
            }
            if (key.usageMonthly != null) {
                if (isNotEmpty()) append(" | ")
                append(formatRoot("$%.2f this month", key.usageMonthly))
            }
            if (key.isFreeTier && isEmpty()) {
                append("Free tier")
            }
            if (!hasKeyCap() && monthlyBudgetUsd == null && isEmpty()) {
                // Spend-only, no cap, no budget: the account balance is not
                // exposed to regular keys — point where it lives.
                append("No key cap — balance at openrouter.ai/credits")
            }
        }

        val balance = credits?.let { it.totalCredits - it.totalUsage }
        if (balance != null) {
            if (rawDisplay.isNotEmpty()) rawDisplay += " · "
            rawDisplay += formatRoot(
                "balance $%.2f (%.2f credits − %.2f used)",
                balance, credits!!.totalCredits, credits.totalUsage,
            )
            if (balance < 0) rawDisplay += " — NEGATIVE, top up"
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = remainingPct,
            resetsAt = null,
            windowHours = 0.0,
            available = true,
            // Prepaid display ONLY when the credits branch actually produced
            // limitDollars — a budget-gauge config (uncapped + monthlyBudget)
            // keeps its BudgetBar (review 1819 precedence fix).
            isPrepaidCreditPool = credits != null && capLimit == null && !budgetBranch,
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

/** Lifetime credit totals from /v1/credits (works with regular keys). */
internal data class OpenRouterCredits(
    val totalCredits: Double,
    val totalUsage: Double,
)

internal data class OpenRouterKeyData(
    val limit: Double?,
    val limitRemaining: Double?,
    val usageDaily: Double?,
    val usageMonthly: Double?,
    val usageTotal: Double?,
    val isFreeTier: Boolean,
)
