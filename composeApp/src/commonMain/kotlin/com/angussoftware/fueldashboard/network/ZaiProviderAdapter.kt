package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
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
class ZaiProviderAdapter(
    override val providerId: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.z.ai",
    customDisplayName: String? = null,
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "z.ai"
    override val providerType: ProviderType = ProviderType.WINDOW_CREDIT

    private val client = SharedHttpClient.client

    companion object {
        private const val QUOTA_PATH = "/api/monitor/usage/quota/limit"
        private const val WINDOW_HOURS = 5.0
        private const val WINDOW_MS = (5 * 60 * 60 * 1000).toLong() // 5 hours
        /** Session (weekly) quota window length — docs: "resets every 7 days". */
        private const val SESSION_WINDOW_HOURS = 168.0

        /** Row type used by Coding Plan (`level: "pro"`) accounts. */
        internal const val CREDIT_LIMIT = "CREDIT_LIMIT"

        /**
         * `unit` values on a CREDIT_LIMIT row, inferred and then verified.
         *
         * The encoding is undocumented, but the plan it describes is not: this
         * provider has a 5-hour window and a weekly one, which is why
         * [WINDOW_HOURS] and [SESSION_WINDOW_HOURS] already exist above. A
         * live Coding Plan account returned exactly two rows — `number 5,
         * unit 3` and `number 1, unit 6` — and 5 hours plus 1 week is the only
         * reading consistent with that plan and with the second row's reset
         * falling 101h out, inside a 168h window.
         *
         * Units beyond these two are left undecoded rather than extrapolated,
         * and every decode is range-checked against the row's own reset (see
         * `creditWindowHours`), so a wrong inference degrades to "length
         * unknown" instead of a confidently wrong hourglass.
         */
        private const val UNIT_HOUR = 3
        private const val UNIT_WEEK = 6
    }

    override suspend fun poll(): ProviderReport {
        val endpoint = "$baseUrl$QUOTA_PATH"
        val response: ZaiQuotaResponse = client.get(endpoint) {
            header(HttpHeaders.Authorization, apiKey)
            header(HttpHeaders.AcceptLanguage, "en-US,en")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }.body()

        if (!response.success) {
            throw RuntimeException("z.ai API error: ${response.msg ?: "unknown error"}")
        }

        return mapToProviderReport(response)
    }

    /**
     * Maps the z.ai quota response to a [ProviderReport].
     *
     * The z.ai API tracks usage as "percentage used" — we convert to "percentage remaining"
     * for the dashboard's fuel-bar UI.
     */
    internal fun mapToProviderReport(
        response: ZaiQuotaResponse,
        /**
         * Injectable so period decoding can be tested deterministically: the
         * credit range check compares a reset against the clock, and a test
         * pinned to a real timestamp would silently start failing once that
         * reset passed.
         */
        now: Long = epochMillis(),
    ): ProviderReport {
        val limits = response.data.limits
        val tokensLimit = limits.firstOrNull { it.type == "TOKENS_LIMIT" }
        val sessionLimit = limits.firstOrNull { it.type == "SESSION_LIMIT" }

        val windows = mutableListOf<ReportWindow>()

        // Parse token limit. When the API omits TOKENS_LIMIT the headline
        // must be UNKNOWN (null), not a fabricated 100% — "100% fuel" from a
        // missing field feeds the waste tiles, advisor regime, and alert
        // suppression with invented data (adversarial review H4).
        val tokensUsedPct: Int? = tokensLimit?.percentage
        val tokensRemaining: Int? = tokensUsedPct?.let { (100 - it).coerceIn(0, 100) }
        // Fallback: if API doesn't return nextResetTime, compute from window size
        val tokenResetTime = tokensLimit?.nextResetTime
        val resetMs = tokenResetTime ?: (now + WINDOW_MS)
        val tokenResetEstimated = tokenResetTime == null

        if (tokensLimit != null) {
            windows.add(
                ReportWindow(
                    name = "5h Token Window",
                    remainingPct = tokensRemaining ?: 100,
                    resetsAt = resetMs,
                    windowHours = WINDOW_HOURS,
                    resetEstimated = tokenResetEstimated,
                ),
            )
        }

        if (sessionLimit != null) {
            val sessionUsed = sessionLimit.percentage
            val sessionRemaining = (100 - sessionUsed).coerceIn(0, 100)
            val sessionResetTime = sessionLimit.nextResetTime
            // The session quota is the WEEKLY limit — "resets every 7 days"
            // (docs.z.ai/devpack/overview). The window LENGTH is a type
            // constant, NOT derivable from resetsAt (deriving it makes
            // total ≡ remaining → hourglass pinned at 100% all week; review
            // 1975). A resetsAt outside sane bounds is treated as absent.
            val sessionResetValid = sessionResetTime?.let { reset ->
                val ms = reset - now
                ms in 0..(45L * 24 * 3_600_000) // sane: 0..45 days out
            } == true
            windows.add(
                ReportWindow(
                    name = "Session",
                    remainingPct = sessionRemaining,
                    resetsAt = if (sessionResetValid) sessionResetTime else null,
                    windowHours = if (sessionResetValid) SESSION_WINDOW_HOURS else 0.0,
                    resetEstimated = !sessionResetValid,
                ),
            )
        }

        // Coding Plan accounts report CREDIT_LIMIT rows instead of the
        // token/session pair, so an account on that plan previously matched
        // nothing here and the gauge read UNAVAILABLE with real quota sitting
        // in the response. Added as a fallback rather than a replacement:
        // accounts that do send TOKENS_LIMIT keep their existing windows.
        val creditLimits = limits.filter { it.type == CREDIT_LIMIT }
        val creditWindows = mutableListOf<ReportWindow>()
        if (windows.isEmpty() && creditLimits.isNotEmpty()) {
            creditLimits.forEachIndexed { index, limit ->
                val hours = creditWindowHours(limit, now)
                creditWindows.add(
                    ReportWindow(
                        // Period when the unit decodes, allowance otherwise —
                        // a stable identity the API states outright, which
                        // still beats "Credits 1" / "Credits 2".
                        name = creditWindowName(limit)
                            ?: limit.usage?.let { "$it credits" }
                            ?: if (creditLimits.size == 1) "Credits" else "Credits ${index + 1}",
                        remainingPct = (100 - limit.percentage).coerceIn(0, 100),
                        resetsAt = limit.nextResetTime,
                        // A validated period, or 0.0 meaning "length unknown"
                        // — percentage and countdown still render, only the
                        // proportional hourglass is withheld. Never derived
                        // from resetsAt, which would make elapsed equal total
                        // and pin it at 100% (review 1975).
                        windowHours = hours ?: 0.0,
                        resetEstimated = limit.nextResetTime == null,
                    ),
                )
            }
            windows.addAll(creditWindows)
        }

        // Headline from ONE window: the most depleted CREDIT row, since that
        // is the one that will actually stop work. %, reset and length all
        // come from that same window rather than mixed across several (PR #84).
        //
        // Scoped to creditWindows on purpose. Scanning every window instead
        // promoted a lone SESSION_LIMIT to headline, which is precisely the
        // fabricated figure adversarial review H4 forbids — a missing
        // TOKENS_LIMIT must leave the headline unknown.
        val creditHeadline = creditWindows.minByOrNull { it.remainingPct ?: Int.MAX_VALUE }

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = tokensRemaining ?: creditHeadline?.remainingPct,
            resetsAt = if (tokensLimit != null) resetMs else creditHeadline?.resetsAt,
            windowHours = if (tokensLimit != null) WINDOW_HOURS else creditHeadline?.windowHours ?: 0.0,
            resetEstimated = if (tokensLimit != null) tokenResetEstimated else creditHeadline?.resetEstimated == true,
            available = windows.isNotEmpty(),
            windows = windows,
            rawDisplay = if (tokensLimit != null) {
                (tokensUsedPct?.let { "tokens:$it%" } ?: "tokens:—") +
                    (sessionLimit?.let { " session:${it.percentage}%" } ?: "")
            } else {
                // One compact line for the binding row only. Repeating every
                // row here duplicated what the bars and their labels already
                // show, and read as noise.
                creditLimits.maxByOrNull { it.percentage }?.let { l ->
                    val used = l.currentValue
                    val total = l.usage
                    if (used != null && total != null) "$used of $total credits used" else null
                }.orEmpty()
            },
        )
    }

    /**
     * Window length for a CREDIT_LIMIT row, or null when it cannot be trusted.
     *
     * Decodes `number` × `unit`, then range-checks the result against the
     * row's own `nextResetTime`: a reset must fall inside a window of the
     * claimed length. That is what makes the inference safe — if the unit
     * mapping is ever wrong, or z.ai changes it, the check fails and the
     * caller falls back to "length unknown" rather than rendering a
     * confidently wrong hourglass. Mirrors the bounds check the session
     * window already applies to its reset.
     */
    private fun creditWindowHours(limit: ZaiQuotaLimit, now: Long): Double? {
        val count = limit.number?.takeIf { it > 0 } ?: return null
        val unitHours = when (limit.unit) {
            UNIT_HOUR -> 1.0
            UNIT_WEEK -> SESSION_WINDOW_HOURS
            else -> return null
        }
        val hours = count * unitHours

        val reset = limit.nextResetTime ?: return hours // nothing to contradict it
        val msRemaining = reset - now
        val windowMs = (hours * 3_600_000).toLong()
        return hours.takeIf { msRemaining in 0..windowMs }
    }

    /** Human label for a decoded period, or null when the unit is unknown. */
    private fun creditWindowName(limit: ZaiQuotaLimit): String? {
        val count = limit.number?.takeIf { it > 0 } ?: return null
        return when (limit.unit) {
            UNIT_HOUR -> "$count-hour"
            UNIT_WEEK -> if (count == 1) "Weekly" else "$count-week"
            else -> null
        }
    }

    override fun close() = Unit
}
