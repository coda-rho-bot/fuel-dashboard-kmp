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
    val type: String = "",
    val percentage: Int = 0,
    @SerialName("nextResetTime")
    val nextResetTime: Long? = null,
)
