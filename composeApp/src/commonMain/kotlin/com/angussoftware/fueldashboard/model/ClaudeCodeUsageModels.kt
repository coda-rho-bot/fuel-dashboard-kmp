package com.angussoftware.fueldashboard.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Claude Code **subscription** usage models (the Max/Pro plan limits).
 *
 * Endpoint: `GET https://api.anthropic.com/api/oauth/usage`
 * Auth: the OAuth access token Claude Code stores locally — NOT an API key.
 * This is the same endpoint the `/usage` slash command reads, and it is the
 * only place plan utilization is exposed. [AnthropicProviderAdapter] cannot
 * see any of it: that one is API-platform shaped (admin key, cost reports,
 * rate-limit headers) and reports spend against the developer console.
 *
 * Abridged response:
 * ```json
 * {
 *   "five_hour": { "utilization": 17.0, "resets_at": "2026-09-24T03:39:59.735686+00:00" },
 *   "seven_day": { "utilization": 77.0, "resets_at": "2026-09-25T22:59:59.735707+00:00" },
 *   "limits": [
 *     { "kind": "session",     "percent": 17, "severity": "normal",  "is_active": false },
 *     { "kind": "weekly_all",  "percent": 77, "severity": "warning", "is_active": true }
 *   ]
 * }
 * ```
 *
 * `utilization` is the percentage **used**, not remaining. `resets_at` is
 * ISO-8601 with sub-second precision and an explicit offset.
 *
 * This endpoint is undocumented and carries a shifting set of experimental
 * top-level keys (codenames that appear and vanish between releases), so it
 * MUST be parsed with `ignoreUnknownKeys`. Every numeric field here is
 * nullable on purpose: `SharedHttpClient` enables `coerceInputValues`, which
 * would quietly turn a null utilization into `0.0` on a non-nullable field —
 * reporting a full tank as an empty one, or an empty one as full.
 */
@Serializable
data class ClaudeCodeUsageResponse(
    @SerialName("five_hour")
    val fiveHour: ClaudeCodeUsageWindow? = null,
    @SerialName("seven_day")
    val sevenDay: ClaudeCodeUsageWindow? = null,
    val limits: List<ClaudeCodeUsageLimit> = emptyList(),
)

@Serializable
data class ClaudeCodeUsageWindow(
    /** Percentage of the window consumed. Null means unknown, never zero. */
    val utilization: Double? = null,
    /** ISO-8601 instant the window rolls over, e.g. "2026-09-24T03:39:59.735686+00:00". */
    @SerialName("resets_at")
    val resetsAt: String? = null,
)

/**
 * One row of the `limits` array. Only [kind] and [severity] are read: the
 * percentages duplicate [ClaudeCodeUsageResponse.fiveHour] / [sevenDay], and
 * severity is the plan's own escalation label ("normal", "warning", …) which
 * is worth surfacing verbatim rather than re-deriving from thresholds.
 */
@Serializable
data class ClaudeCodeUsageLimit(
    /** "session" = the 5-hour window, "weekly_all" = the 7-day window. */
    val kind: String = "",
    val severity: String? = null,
) {
    companion object {
        const val KIND_SESSION = "session"
        const val KIND_WEEKLY_ALL = "weekly_all"
    }
}
