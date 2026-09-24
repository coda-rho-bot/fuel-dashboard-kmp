package com.angussoftware.fueldashboard.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * z.ai quota API response models.
 *
 * Endpoint: `https://api.z.ai/api/monitor/usage/quota/limit`
 * Auth: Bearer token (the z.ai API key, without "Bearer " prefix — the API expects the raw key).
 *
 * The response looks like:
 * ```json
 * {
 *   "success": true,
 *   "data": {
 *     "limits": [
 *       {
 *         "type": "TOKENS_LIMIT",
 *         "percentage": 42,
 *         "nextResetTime": 1234567890000
 *       }
 *     ]
 *   }
 * }
 * ```
 *
 * `percentage` is the percentage **used**, not remaining.
 * `nextResetTime` is epoch milliseconds (UTC).
 * Reset is a sliding 5-hour window in UTC+8 (Beijing timezone).
 */
@Serializable
data class ZaiQuotaResponse(
    val success: Boolean = false,
    val data: ZaiQuotaData = ZaiQuotaData(),
    val msg: String? = null,
)

@Serializable
data class ZaiQuotaData(
    val limits: List<ZaiQuotaLimit> = emptyList(),
)

@Serializable
data class ZaiQuotaLimit(
    /**
     * `TOKENS_LIMIT`, `SESSION_LIMIT`, or `CREDIT_LIMIT`.
     *
     * Coding Plan accounts (`level: "pro"`) report `CREDIT_LIMIT` rows rather
     * than the token/session pair, verified live against a pro account.
     */
    val type: String = "",
    val percentage: Int = 0,
    @SerialName("nextResetTime")
    val nextResetTime: Long? = null,
    /**
     * Period descriptor on `CREDIT_LIMIT` rows: [number] of [unit].
     *
     * The encoding is undocumented and deliberately NOT decoded. A live pro
     * account returned `unit: 6, number: 1` alongside a reset four days out,
     * which rules out the obvious reading, and inventing a window length pins
     * the hourglass at 100% forever (review 1975). Carried so the shape is
     * recorded, unused for anything that would be wrong if the guess were.
     */
    val unit: Int? = null,
    val number: Int? = null,
    /** Total allowance for the period. */
    val usage: Long? = null,
    /** Consumed so far. */
    val currentValue: Long? = null,
    val remaining: Long? = null,
)
