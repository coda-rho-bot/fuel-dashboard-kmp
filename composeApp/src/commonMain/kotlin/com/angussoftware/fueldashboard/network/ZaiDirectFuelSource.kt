package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.FuelResponse
import com.angussoftware.fueldashboard.model.FuelSource
import com.angussoftware.fueldashboard.model.Provider
import com.angussoftware.fueldashboard.model.ProviderWindowInfo
import com.angussoftware.fueldashboard.model.Window
import com.angussoftware.fueldashboard.model.ZaiQuotaLimit
import com.angussoftware.fueldashboard.model.ZaiQuotaResponse
import com.angussoftware.fueldashboard.util.epochMillis
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Polls the z.ai quota API directly — no orchestrator required.
 *
 * Endpoint: `https://api.z.ai/api/monitor/usage/quota/limit`
 * Auth: raw API key in the Authorization header (no "Bearer " prefix — z.ai expects the key directly).
 *
 * The response contains usage percentages for TOKENS_LIMIT and optionally SESSION_LIMIT.
 * The percentage is **used** (not remaining), so remaining = 100 - percentage.
 *
 * Reset is a sliding 5-hour window. The `nextResetTime` field (epoch ms, UTC) tells us
 * when the window resets. Window position is computed from elapsed time within the window.
 */
class ZaiDirectFuelSource(
    private val apiKey: String,
) : FuelSource {

    override val displayName: String = "z.ai Direct"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.z.ai/api/monitor/usage/quota/limit"
        private const val WINDOW_HOURS = 5.0
        private const val WINDOW_MS = (5 * 60 * 60 * 1000).toLong() // 5 hours
    }

    override suspend fun getFuel(): FuelResponse {
        val response: ZaiQuotaResponse = client.get(ENDPOINT) {
            header(HttpHeaders.Authorization, apiKey)
            header(HttpHeaders.AcceptLanguage, "en-US,en")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }.body()

        if (!response.success) {
            throw RuntimeException("z.ai API error: ${response.msg ?: "unknown error"}")
        }

        return mapToFuelResponse(response)
    }

    /**
     * Maps the z.ai quota response to the dashboard's [FuelResponse] model.
     *
     * The z.ai API tracks usage as "percentage used" — we convert to "percentage remaining"
     * for the dashboard's fuel-bar UI which expects remainingPct.
     */
    private fun mapToFuelResponse(response: ZaiQuotaResponse): FuelResponse {
        val limits = response.data.limits
        val tokensLimit = limits.firstOrNull { it.type == "TOKENS_LIMIT" }
        val sessionLimit = limits.firstOrNull { it.type == "SESSION_LIMIT" }

        val now = epochMillis()

        // Parse token limit
        val tokensUsedPct = tokensLimit?.percentage ?: 0
        val tokensRemaining = (100 - tokensUsedPct).coerceIn(0, 100)
        val resetMs = tokensLimit?.nextResetTime

        // Compute window position (0.0 = window just started, 1.0 = window about to reset)
        val windowPosition = resetMs?.let { reset ->
            val windowStart = reset - WINDOW_MS
            val elapsed = now - windowStart
            (elapsed.toDouble() / WINDOW_MS).coerceIn(0.0, 1.0)
        } ?: 0.0

        // Build provider
        val windows = mutableMapOf<String, Window>()

        if (tokensLimit != null) {
            windows["5h Token Window"] = Window(
                remainingPct = tokensRemaining,
                resetsAt = resetMs,
                windowHours = WINDOW_HOURS,
            )
        }

        if (sessionLimit != null) {
            val sessionUsed = sessionLimit.percentage
            val sessionRemaining = (100 - sessionUsed).coerceIn(0, 100)
            windows["Session"] = Window(
                remainingPct = sessionRemaining,
                resetsAt = sessionLimit.nextResetTime,
                windowHours = WINDOW_HOURS, // sessions share the same window
            )
        }

        val provider = Provider(
            name = "z.ai",
            remainingPct = tokensRemaining,
            available = true,
            resetMs = resetMs,
            windows = windows,
        )

        return FuelResponse(
            ts = now,
            providers = mapOf("z.ai" to provider),
            providerResets = resetMs?.let { mapOf("z.ai" to mapOf("tokens" to it)) } ?: emptyMap(),
            providerWindows = mapOf(
                "z.ai" to ProviderWindowInfo(
                    windowHours = WINDOW_HOURS,
                    position = windowPosition,
                ),
            ),
            burnRatePctPerHr = 0.0, // Computed by ViewModel from history
            recommendedModel = "", // Not available in direct mode
            modManaged = emptyMap(),
            surplusAlert = false,
        )
    }

    /**
     * Extracts the raw token/session usage percentages from the last response.
     * Used by the ViewModel to save history snapshots for burn-rate computation.
     */
    suspend fun poll(): Pair<Int, Int?> = try {
        val response: ZaiQuotaResponse = client.get(ENDPOINT) {
            header(HttpHeaders.Authorization, apiKey)
            header(HttpHeaders.AcceptLanguage, "en-US,en")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }.body()

        if (!response.success) return Pair(0, null)

        val tokens = response.data.limits.firstOrNull { it.type == "TOKENS_LIMIT" }
        val session = response.data.limits.firstOrNull { it.type == "SESSION_LIMIT" }
        Pair(tokens?.percentage ?: 0, session?.percentage)
    } catch (e: Exception) {
        Pair(0, null)
    }

    fun close() {
        client.close()
    }
}
