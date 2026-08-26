package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Polls the xAI (Grok) API for key status.
 *
 * **What this surface exposes (and what it doesn't):**
 *
 * xAI's regular API-key surface has no balance/credits endpoint — prepaid
 * credit balances live behind the separate Management API
 * (management-api.x.ai, management key required), and per-request costs are
 * returned inline on inference responses (`cost_in_usd_ticks`) rather than
 * as a queryable total.
 *
 * What we CAN do with a regular key:
 *
 * - **`GET /v1/api-key`**: key metadata — name, status, permissions. Proves
 *  the key is valid and shows which key is active.
 *
 * **Auth**: `Authorization: Bearer $XAI_API_KEY`
 *
 * **Fuel type**: RATE_LIMIT placeholder (no throttle or spend data on this
 * surface — the report is availability + key metadata, no gauge).
 */
class XaiProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.x.ai",
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "xAI"
    override val providerType: ProviderType = ProviderType.RATE_LIMIT

    private val client = SharedHttpClient.client

    companion object {
        private const val KEY_PATH = "/v1/api-key"
    }

    override suspend fun poll(): ProviderReport {
        val data = fetchKeyInfo() ?: return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = null,
            available = false,
            rawDisplay = "Key info unavailable — check API key",
        )
        return buildReport(data)
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
            XaiKeyInfo(
                name = s("name"),
                status = s("status"),
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun buildReport(info: XaiKeyInfo): ProviderReport {
        val rawDisplay = buildString {
            info.name?.let { append(it) }
            info.status?.let {
                if (isNotEmpty()) append(" · ")
                append(it)
            }
            if (isEmpty()) append("Key active")
            else append(" · balance in console")
        }
        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = null,
            resetsAt = null,
            windowHours = 0.0,
            available = true,
            rawDisplay = rawDisplay,
        )
    }

    override fun close() = Unit
}

internal data class XaiKeyInfo(
    val name: String?,
    val status: String?,
)
