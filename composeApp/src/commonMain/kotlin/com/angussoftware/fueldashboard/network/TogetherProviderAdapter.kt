package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.util.formatRoot
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * Polls the Together AI billing usage API.
 *
 * **Single data source:**
 *
 * - **`GET /v1/billing/usage`**: per-date usage rows (`data[]` with `date`,
 *   `model_id`, `input_tokens`, `output_tokens`, `total_cost`). Requires an
 *   API key with **billing scope** (restricted keys 403). We sum the current
 *   calendar month's `total_cost` for the monthly spend figure.
 *
 * **Auth**: `Authorization: Bearer $TOGETHER_API_KEY`
 *
 * **Fuel type**: SPEND_BUDGET. Together is fully prepaid (credits), but the
 * remaining balance is dashboard-only — no balance API on this surface. The
 * gauge is monthly usage vs a user-set monthly budget (the same UX as the
 * OpenAI/Anthropic adapters). Without a budget: spend-only display.
 *
 * On 403 (key lacks billing scope) the adapter reports unavailable with a
 * hint, since that's an actionable key problem, not a provider outage.
 */
class TogetherProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.together.xyz",
    private val monthlyBudgetUsd: Double? = null,
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Together AI"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    private val client = SharedHttpClient.client

    companion object {
        private const val USAGE_PATH = "/v1/billing/usage"
    }

    override suspend fun poll(): ProviderReport {
        return try {
            val response: HttpResponse = client.get("$baseUrl$USAGE_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            when {
                response.status.isSuccess() -> {
                    val monthlyCost = sumMonthCost(response.bodyAsText())
                    if (monthlyCost == null) unavailable("Usage data unparseable") else buildReport(monthlyCost)
                }
                response.status.value == 403 -> unavailable("Key lacks billing scope — regenerate in console")
                else -> unavailable("Usage endpoint unavailable (${response.status.value})")
            }
        } catch (_: Exception) {
            unavailable("Usage endpoint unreachable")
        }
    }

    /** Sums total_cost for rows in the current calendar month (UTC). */
    internal fun sumMonthCost(body: String): Double? {
        return try {
            val data = SharedHttpClient.json.parseToJsonElement(body)
                .jsonObject["data"]?.jsonArray ?: return null
            val monthPrefix = currentUtcMonthPrefix()
            var total = 0.0
            var found = false
            for (row in data) {
                val obj = row.jsonObject
                val date = (obj["date"] as? JsonPrimitive)?.content ?: continue
                if (!date.startsWith(monthPrefix)) continue
                val cost = (obj["total_cost"] as? JsonPrimitive)?.doubleOrNull ?: continue
                total += cost
                found = true
            }
            if (found) total else 0.0
        } catch (_: Exception) {
            null
        }
    }

    private fun currentUtcMonthPrefix(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        return formatRoot("%04d-%02d", now.year, now.monthNumber)
    }

    internal fun buildReport(monthlyCost: Double): ProviderReport {
        val usedDollars: Double?
        val limitDollars: Double?
        val remainingPct: Int?

        if (monthlyBudgetUsd != null && monthlyBudgetUsd > 0) {
            usedDollars = monthlyCost
            limitDollars = monthlyBudgetUsd
            val usedPct = (monthlyCost / monthlyBudgetUsd * 100.0).coerceIn(0.0, 100.0)
            remainingPct = (100 - usedPct.roundToInt()).coerceIn(0, 100)
        } else {
            usedDollars = monthlyCost
            limitDollars = null
            remainingPct = null
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
            rawDisplay = formatRoot("$%.2f this month", monthlyCost) + " · balance in console",
        )
    }

    private fun unavailable(reason: String) = ProviderReport(
        providerId = providerId,
        displayName = displayName,
        type = providerType,
        remainingPct = null,
        available = false,
        rawDisplay = reason,
    )

    override fun close() = Unit
}
