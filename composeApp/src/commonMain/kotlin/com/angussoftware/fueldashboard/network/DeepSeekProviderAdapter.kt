package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.angussoftware.fueldashboard.util.formatRoot

/**
 * Polls the DeepSeek API for prepaid credit balance.
 *
 * **Single data source:**
 *
 * - **Balance API**: `GET /user/balance`
 *   Returns real-time prepaid balance with breakdown of granted (free) vs
 *   topped-up (paid) credits.
 *   - **Auth**: `Authorization: Bearer $API_KEY` (standard API key, no admin key needed)
 *   - **Real-time**: No delay — balance is updated immediately after each API call.
 *
 * **Response format:**
 * ```json
 * {
 *   "is_available": true,
 *   "balance_infos": [{
 *     "currency": "USD",
 *     "total_balance": "10.05",
 *     "granted_balance": "10.00",
 *     "topped_up_balance": "0.05"
 *   }]
 * }
 * ```
 *
 * **Fuel type**: SPEND_BUDGET (balance-based). The total balance represents
 * remaining prepaid credit. DeepSeek does not expose spend history or rate
 * limit headers via API — only the current balance.
 *
 * The [limitDollars] field carries the total remaining balance (with
 * [usedDollars] = 0), so BudgetBar shows "$0.00 / $X.XX" — representing
 * available prepaid credit, not monthly spend.
 */
class DeepSeekProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "DeepSeek"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    private val json = SharedHttpClient.json
    private val client = SharedHttpClient.client

    companion object {
        private const val BALANCE_PATH = "/user/balance"
    }

    override suspend fun poll(): ProviderReport {
        val balance = fetchBalance()
        return buildReport(balance)
    }

    // -----------------------------------------------------------------------
    // Balance API
    // -----------------------------------------------------------------------

    /**
     * Fetches the current prepaid balance from the /user/balance endpoint.
     *
     * Returns null if the API call fails.
     */
    private suspend fun fetchBalance(): BalanceData? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$BALANCE_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            if (!response.status.isSuccess()) return null

            val body: JsonObject = json.parseToJsonElement(response.bodyAsText()).jsonObject

            val isAvailable = body["is_available"]?.jsonPrimitive?.booleanOrNull ?: true
            val balanceInfos = body["balance_infos"] as? JsonArray ?: return null

            val info = (balanceInfos.firstOrNull() as? JsonObject) ?: return null

            BalanceData(
                isAvailable = isAvailable,
                currency = info["currency"]?.jsonPrimitive?.contentOrNull ?: "USD",
                totalBalance = info["total_balance"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                grantedBalance = info["granted_balance"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                toppedUpBalance = info["topped_up_balance"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            )
        } catch (e: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Report Building
    // -----------------------------------------------------------------------

    internal fun buildReport(balance: BalanceData?): ProviderReport {
        if (balance == null) {
            return ProviderReport(
                providerId = providerId,
                displayName = displayName,
                type = providerType,
                remainingPct = null,
                available = false,
                rawDisplay = "Balance unavailable",
            )
        }

        // Build window: show balance as remaining credit
        val windows = mutableListOf<ReportWindow>()

        // Use total balance as the "remaining" indicator
        // We don't have an original balance to compute percentage from,
        // so we show absolute dollar amounts
        windows.add(
            ReportWindow(
                name = "Credit Balance",
                remainingPct = null, // no percentage concept without knowing original amount
                resetsAt = null,
                windowHours = 0.0,
            ),
        )

        val rawDisplay = buildString {
            append(formatRoot("$%.2f", balance.totalBalance))
            append(" ${balance.currency}")
            if (balance.grantedBalance > 0.0 || balance.toppedUpBalance > 0.0) {
                append(formatRoot(" (granted: $%.2f", balance.grantedBalance))
                append(formatRoot(", topped up: $%.2f)", balance.toppedUpBalance))
            }
            if (!balance.isAvailable) {
                append(" | INSUFFICIENT BALANCE")
            }
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = null,
            available = balance.isAvailable,
            windows = windows,
            // Prepaid credit pool: limitDollars carries the remaining balance
            // and the UI renders a "credit remaining" display — NOT the
            // used-of-budget spend bar (which misread as a monthly limit).
            isPrepaidCreditPool = true,
            usedDollars = null,
            limitDollars = balance.totalBalance,
            rawDisplay = rawDisplay,
        )
    }

    override fun close() = Unit
}

// -----------------------------------------------------------------------
// Internal data classes
// -----------------------------------------------------------------------

internal data class BalanceData(
    val isAvailable: Boolean,
    val currency: String,
    val totalBalance: Double,
    val grantedBalance: Double,
    val toppedUpBalance: Double,
)
