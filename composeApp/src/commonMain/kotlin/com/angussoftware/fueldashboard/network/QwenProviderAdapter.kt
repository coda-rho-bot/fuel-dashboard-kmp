package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.util.epochMillis
import com.angussoftware.fueldashboard.util.formatRoot
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * Polls the Alibaba DashScope (Qwen / Model Studio) quotas API.
 *
 * **Single data source:**
 *
 * - **`GET /api/v1/quotas`**: near-real-time aggregate with Bearer API key.
 *   Documented fields (OpenUsage integration + Alibaba docs):
 *   - `data.rate_limit.rpm` / `data.rate_limit.tpm`
 *   - `data.spend_limit` (USD cap), `data.daily_spend`, `data.monthly_spend`
 *   - `data.tokens_used`, `data.requests_used`
 *
 * **Auth**: `Authorization: Bearer $DASHSCOPE_API_KEY`
 *
 * **Fuel type**: SPEND_BUDGET (calendar-month billing period). Monthly
 * spend vs spend_limit is the gauge; falls back to a user-set monthly
 * budget when the account has no spend limit configured. RPM/TPM windows
 * are attached when present.
 *
 * Billing is reported in USD even when the account is CNY-funded.
 */
class QwenProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://dashscope.aliyuncs.com/api",
    private val monthlyBudgetUsd: Double? = null,
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Qwen"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    private val client = SharedHttpClient.client

    companion object {
        private const val QUOTAS_PATH = "/v1/quotas"
    }

    override suspend fun poll(): ProviderReport {
        val data = fetchQuotas() ?: return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = null,
            available = false,
            rawDisplay = "Quota data unavailable — check API key",
        )
        return buildReport(data)
    }

    private suspend fun fetchQuotas(): QwenQuotaData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$QUOTAS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (!response.status.isSuccess()) return null
            parseQuotasResponse(response.bodyAsText()) ?: return null
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseQuotasResponse(body: String): QwenQuotaData? {
        return try {
            val data = SharedHttpClient.json.parseToJsonElement(body)
                .jsonObject["data"]?.jsonObject ?: return null
            fun d(field: String): Double? =
                (data[field] as? JsonPrimitive)?.doubleOrNull
            val rateLimit = data["rate_limit"]?.jsonObject
            QwenQuotaData(
                spendLimit = d("spend_limit"),
                dailySpend = d("daily_spend"),
                monthlySpend = d("monthly_spend"),
                tokensUsed = d("tokens_used"),
                requestsUsed = d("requests_used"),
                rpm = rateLimit?.let { (it["rpm"] as? JsonPrimitive)?.doubleOrNull }?.toInt(),
                tpm = rateLimit?.let { (it["tpm"] as? JsonPrimitive)?.doubleOrNull }?.toInt(),
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun buildReport(q: QwenQuotaData): ProviderReport {
        val windows = mutableListOf<ReportWindow>()
        val usedDollars: Double?
        val limitDollars: Double?
        val remainingPct: Int?

        when {
            q.monthlySpend != null && q.spendLimit != null && q.spendLimit > 0 -> {
                usedDollars = q.monthlySpend
                limitDollars = q.spendLimit
                val usedPct = (q.monthlySpend / q.spendLimit * 100.0).coerceIn(0.0, 100.0)
                remainingPct = (100 - usedPct.roundToInt()).coerceIn(0, 100)
            }
            q.monthlySpend != null && monthlyBudgetUsd != null && monthlyBudgetUsd > 0 -> {
                usedDollars = q.monthlySpend
                limitDollars = monthlyBudgetUsd
                val usedPct = (q.monthlySpend / monthlyBudgetUsd * 100.0).coerceIn(0.0, 100.0)
                remainingPct = (100 - usedPct.roundToInt()).coerceIn(0, 100)
            }
            else -> {
                usedDollars = null
                limitDollars = null
                remainingPct = null
            }
        }

        if (q.rpm != null && q.rpm > 0) {
            windows.add(ReportWindow(name = "RPM", remainingPct = null, resetsAt = epochMillis() + 60_000, windowHours = 1.0 / 60.0))
        }
        if (q.tpm != null && q.tpm > 0) {
            windows.add(ReportWindow(name = "TPM", remainingPct = null, resetsAt = epochMillis() + 60_000, windowHours = 1.0 / 60.0))
        }

        val rawDisplay = buildString {
            if (q.monthlySpend != null && limitDollars != null) {
                append(formatRoot("$%.2f of $%.2f this month", q.monthlySpend, limitDollars))
            } else if (q.dailySpend != null) {
                append(formatRoot("$%.2f today", q.dailySpend))
            }
            if (q.tokensUsed != null) {
                if (isNotEmpty()) append(" | ")
                append(formatRoot("%.0fM tokens", q.tokensUsed / 1_000_000.0))
            }
            if (q.requestsUsed != null) {
                if (isNotEmpty()) append(" | ")
                append(formatRoot("%.0fk req", q.requestsUsed / 1000.0))
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
            usedDollars = usedDollars,
            limitDollars = limitDollars,
            rawDisplay = rawDisplay,
        )
    }

    override fun close() = Unit
}

internal data class QwenQuotaData(
    val spendLimit: Double?,
    val dailySpend: Double?,
    val monthlySpend: Double?,
    val tokensUsed: Double?,
    val requestsUsed: Double?,
    val rpm: Int?,
    val tpm: Int?,
)
