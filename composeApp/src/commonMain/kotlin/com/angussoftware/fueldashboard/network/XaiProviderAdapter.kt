package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.util.formatRoot
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * Polls the xAI (Grok) API for key status and prepaid balance.
 *
 * **Single data source:**
 *
 * - **`GET /v1/api-key`** (regular API key, Bearer auth): key metadata and
 *   prepaid credit fields. Wire fields (xAI docs + OpenUsage, Aug 2026):
 *   - `name` — key label
 *   - `api_key_blocked` / `api_key_disabled` / `team_blocked` — booleans
 *     (there is no `status` string)
 *   - `remaining_balance` / `spent_balance` / `total_granted` — USD
 *
 * **Fuel type**: SPEND_BUDGET — usedDollars = spent_balance,
 * limitDollars = total_granted, gauge from remaining_balance. When the
 * balance fields are absent (older surface / no grant), the report degrades
 * to key-name display without a gauge.
 *
 * The separate Management API (management-api.x.ai) offers richer team
 * billing, but requires a management key — deliberately not used.
 */
class XaiProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.x.ai",
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "xAI"
    override val providerType: ProviderType = ProviderType.SPEND_BUDGET

    private val client = SharedHttpClient.client

    companion object {
        private const val KEY_PATH = "/v1/api-key"
    }

    override suspend fun poll(): ProviderReport {
        val info = fetchKeyInfo() ?: return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = null,
            available = false,
            rawDisplay = "Key info unavailable — check API key",
        )
        return buildReport(info)
    }

    private suspend fun fetchKeyInfo(): XaiKeyInfo? {
        return try {
            val response: HttpResponse = client.get("$baseUrl$KEY_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (!response.status.isSuccess()) return null
            parseKeyResponse(response.bodyAsText()) ?: return null
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseKeyResponse(body: String): XaiKeyInfo? {
        return try {
            val root = SharedHttpClient.json.parseToJsonElement(body).jsonObject
            fun s(field: String): String? =
                (root[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            fun d(field: String): Double? =
                (root[field] as? JsonPrimitive)?.doubleOrNull
            fun b(field: String): Boolean =
                (root[field] as? JsonPrimitive)?.booleanOrNull ?: false
            XaiKeyInfo(
                name = s("name"),
                blocked = b("api_key_blocked") || b("api_key_disabled") || b("team_blocked"),
                remainingBalance = d("remaining_balance"),
                spentBalance = d("spent_balance"),
                totalGranted = d("total_granted"),
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun buildReport(info: XaiKeyInfo): ProviderReport {
        val hasBalance = info.totalGranted != null && info.totalGranted > 0 && info.remainingBalance != null

        val usedDollars: Double?
        val limitDollars: Double?
        val remainingPct: Int?

        if (hasBalance) {
            usedDollars = info.spentBalance ?: ((info.totalGranted!! - info.remainingBalance!!).coerceAtLeast(0.0))
            limitDollars = info.totalGranted
            remainingPct = (info.remainingBalance!! / info.totalGranted!! * 100).coerceIn(0.0, 100.0).roundToInt()
        } else {
            usedDollars = null
            limitDollars = null
            remainingPct = null
        }

        val rawDisplay = buildString {
            if (hasBalance) {
                append(formatRoot("$%.2f left of $%.2f", info.remainingBalance, info.totalGranted))
            }
            info.name?.let {
                if (isNotEmpty()) append(" | ")
                append(it)
            }
            if (info.blocked) {
                if (isNotEmpty()) append(" | ")
                append("BLOCKED")
            }
            if (isEmpty()) append("Key active")
            else if (!hasBalance) append(" · balance in console")
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = remainingPct,
            resetsAt = null,
            windowHours = 0.0,
            available = !info.blocked,
            usedDollars = usedDollars,
            limitDollars = limitDollars,
            rawDisplay = rawDisplay,
        )
    }

    override fun close() = Unit
}

internal data class XaiKeyInfo(
    val name: String?,
    val blocked: Boolean,
    val remainingBalance: Double?,
    val spentBalance: Double?,
    val totalGranted: Double?,
)
