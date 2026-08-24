package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.LettaBillingResponse
import com.angussoftware.fueldashboard.model.LettaQuotaDetail
import com.angussoftware.fueldashboard.model.LettaQuotaResponse
import com.angussoftware.fueldashboard.model.LettaTierInfo
import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Polls the Letta Cloud quota and billing APIs.
 *
 * Two endpoints are queried:
 *
 * 1. **Quota endpoint**: `GET /v1/organizations/self/quotas`
 *    Returns categorical bucket values for `lettaTier`:
 *    - `bucket`: empty, low, medium, high, full
 *    - `dailyBucket`: same categorical scale for daily window
 *    - `quotaWindowEnd` / `dailyQuotaWindowEnd`: ISO timestamps for window resets
 *
 * 2. **Billing endpoint**: `GET /v1/organizations/self/billing-info`
 *    Returns exact usage percentages via `quotaDetails[]`:
 *    - Entry with `tier == "letta-tier"` has `percentUsed` (0-100)
 *    - When available, exact data overrides categorical buckets
 *
 * Auth: `Authorization: Bearer <api-key>`
 *
 * Bucket-to-percentage mapping (per Letta support, confirmed by Ezra):
 *   empty=0%, low=25%, medium=50%, high=75%, full=100%
 *
 * Per the orchestrator's provider-monitor.ts, only `lettaTier` is parsed —
 * basic/standard/premium tiers have zero limits on Pro plan.
 */
class LettaCloudProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.letta.com",
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Letta Cloud"
    override val providerType: ProviderType = ProviderType.WINDOW_CREDIT

    private val json = SharedHttpClient.json
    private val client = SharedHttpClient.client

    companion object {
        private const val QUOTA_PATH = "/v1/organizations/self/quotas"
        private const val BILLING_PATH = "/v1/organizations/self/billing-info"
        private const val SHORT_WINDOW_HOURS = 4.0
        private const val DAILY_WINDOW_HOURS = 24.0

        /**
         * Official bucket mapping per Letta support (Ezra).
         * empty=0%, low=25%, medium=50%, high=75%, full=100%
         */
        private val BUCKET_PCT = mapOf(
            "empty" to 0,
            "low" to 25,
            "medium" to 50,
            "high" to 75,
            "full" to 100,
        )
    }

    override suspend fun poll(): ProviderReport {
        // 1. Query quota endpoint for categorical buckets + window ends
        val quotaResponse: LettaQuotaResponse = try {
            client.get("$baseUrl$QUOTA_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }.body()
        } catch (e: Exception) {
            throw RuntimeException("Letta Cloud quota API error: ${e.message}", e)
        }

        // 2. Query billing endpoint for exact percentages + credit numbers
        val billing = fetchExactUsage()

        // 3. Build report from quota data, using exact percentage when available
        return buildReport(quotaResponse, billing)
    }

    /**
     * Queries the billing-info endpoint for exact quota usage and credit numbers.
     * Returns null if billing data is unavailable or the letta-tier entry isn't found.
     *
     * Ported from orchestrator's `fetchBillingInfo()` + `parseLetta()` billing logic.
     */
    private suspend fun fetchExactUsage(): BillingData? {
        return try {
            val billingResponse: LettaBillingResponse =
                client.get("$baseUrl$BILLING_PATH") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }.body()

            val detail = billingResponse.quotaDetails
                ?.firstOrNull { it.tier == "letta-tier" }

            BillingData(
                percentUsed = detail?.percentUsed,
                used = detail?.used,
                limit = detail?.limit,
                totalCredits = billingResponse.totalCredits,
                isLow = detail?.isLow,
                billingPeriodEnd = billingResponse.billingPeriodEnd,
            )
        } catch (e: Exception) {
            null
        }
    }

    internal data class BillingData(
        val percentUsed: Double?,
        val used: Int?,
        val limit: Int?,
        val totalCredits: Int?,
        val isLow: Boolean?,
        val billingPeriodEnd: String?,
    )

    /**
     * Builds the ProviderReport from quota + billing data.
     *
     * Logic ported from orchestrator's `parseLetta()`:
     * - When exact percentage is available, it overrides categorical buckets for both windows
     * - Only lettaTier is parsed (basic/standard/premium have zero limits on Pro)
     * - Available = true even when exhausted (pay-as-you-go credits or BYOK may still serve)
     */
    internal fun buildReport(
        quotaResponse: LettaQuotaResponse,
        billing: BillingData?,
    ): ProviderReport {
        val lettaTier: LettaTierInfo? = quotaResponse.lettaTier

        // Parse ISO timestamps to epoch millis
        val windowEnd = quotaResponse.quotaWindowEnd?.let { parseIsoToEpochMs(it) }
        val dailyWindowEnd = quotaResponse.dailyQuotaWindowEnd?.let { parseIsoToEpochMs(it) }

        val windows = mutableListOf<ReportWindow>()

        // Exact percentage overrides categorical when available
        val exactUsedPct = billing?.percentUsed
        val exactRemaining = exactUsedPct?.let { (100.0 - it).coerceIn(0.0, 100.0).roundToInt() }

        var unknownBuckets: List<String> = emptyList()
        if (lettaTier != null) {
            val shortBucket = lettaTier.bucket ?: "unknown"
            val dailyBucket = lettaTier.dailyBucket ?: "unknown"

            val shortPct = exactRemaining ?: bucketToPct(shortBucket)
            val dailyPct = exactRemaining ?: bucketToPct(dailyBucket)

            // Flag unmapped bucket names so "unknown" is visible, not silent
            unknownBuckets = listOf(shortBucket, dailyBucket)
                .filter { exactRemaining == null && BUCKET_PCT[it] == null }
                .distinct()

            // Short window (~4h)
            if (windowEnd != null) {
                windows.add(
                    ReportWindow(
                        name = "4h Quota Window",
                        remainingPct = shortPct,
                        resetsAt = windowEnd,
                        windowHours = SHORT_WINDOW_HOURS,
                    ),
                )
            }

            // Daily window (~24h)
            if (dailyWindowEnd != null) {
                windows.add(
                    ReportWindow(
                        name = "Daily Quota Window",
                        remainingPct = dailyPct,
                        resetsAt = dailyWindowEnd,
                        windowHours = DAILY_WINDOW_HOURS,
                    ),
                )
            }

            // Fallback: no window ends available — use the more pessimistic bucket
            if (windows.isEmpty()) {
                val worstPct = listOfNotNull(shortPct, dailyPct).minOrNull()
                windows.add(
                    ReportWindow(
                        name = "Quota",
                        remainingPct = worstPct,
                        resetsAt = null,
                        windowHours = 0.0,
                    ),
                )
            }
        }

        // Overall remaining: worst across windows, or exact if available
        val overallRemaining = exactRemaining
            ?: windows.mapNotNull { it.remainingPct }.minOrNull()

        val rawDisplay = buildString {
            append("lettaTier:")
            append(lettaTier?.bucket ?: "?")
            append("/")
            append(lettaTier?.dailyBucket ?: "?")
            if (exactUsedPct != null) {
                append(" used:${exactUsedPct.roundToInt()}%")
            }
            if (billing?.used != null && billing.limit != null) {
                append(" credits:${billing.used}/${billing.limit}")
            }
            if (billing?.totalCredits != null) {
                append(" total:${billing.totalCredits}")
            }
            if (billing?.isLow == true) {
                append(" LOW")
            }
            if (unknownBuckets.isNotEmpty()) {
                append(" unknown-bucket:${unknownBuckets.joinToString(",")}")
            }
        }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = overallRemaining,
            resetsAt = windowEnd ?: dailyWindowEnd,
            windowHours = SHORT_WINDOW_HOURS,
            available = true,
            windows = windows,
            rawDisplay = rawDisplay,
            creditsUsed = billing?.used,
            creditsLimit = billing?.limit,
            creditsTotal = billing?.totalCredits,
            creditsLow = billing?.isLow == true,
            creditsResetAt = billing?.billingPeriodEnd?.let { parseIsoToEpochMs(it) },
        )
    }

    /**
     * Maps a categorical bucket to remaining pct. Unknown bucket names
     * (e.g. Letta introducing new tiers) return null — displayed as
     * "unknown" — instead of a false 0%/CRITICAL.
     */
    private fun bucketToPct(bucket: String): Int? =
        BUCKET_PCT[bucket]

    /**
     * Parses an ISO-8601 timestamp to epoch milliseconds.
     * Returns null for unparseable values.
     *
     * Uses kotlinx-datetime's Instant parser for KMP compatibility.
     */
    private fun parseIsoToEpochMs(iso: String): Long? = try {
        kotlinx.datetime.Instant.parse(iso).toEpochMilliseconds()
    } catch (e: Exception) {
        null
    }

    override fun close() = Unit
}
