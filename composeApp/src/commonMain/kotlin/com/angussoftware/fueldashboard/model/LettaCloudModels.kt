package com.angussoftware.fueldashboard.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Letta Cloud quota API response models.
 *
 * Quota endpoint: `GET /v1/organizations/self/quotas`
 * Billing endpoint: `GET /v1/organizations/self/billing-info`
 * Auth: `Authorization: Bearer <api-key>`
 *
 * The quota endpoint returns `lettaTier` with categorical buckets:
 * empty=0%, low=25%, medium=50%, high=75%, full=100%
 *
 * The billing endpoint returns exact percentage via `quotaDetails[].percentUsed`.
 * When exact data is available, it overrides the categorical buckets.
 */

// ---- Quota endpoint response ----

@Serializable
data class LettaQuotaResponse(
    @SerialName("lettaTier")
    val lettaTier: LettaTierInfo? = null,
    @SerialName("quotaWindowEnd")
    val quotaWindowEnd: String? = null,
    @SerialName("dailyQuotaWindowEnd")
    val dailyQuotaWindowEnd: String? = null,
)

@Serializable
data class LettaTierInfo(
    val bucket: String? = null,
    @SerialName("dailyBucket")
    val dailyBucket: String? = null,
)

// ---- Billing endpoint response ----

@Serializable
data class LettaBillingResponse(
    @SerialName("quotaDetails")
    val quotaDetails: List<LettaQuotaDetail>? = null,
    @SerialName("totalCredits")
    val totalCredits: Int? = null,
    @SerialName("billingTier")
    val billingTier: String? = null,
    @SerialName("subscriptionStatus")
    val subscriptionStatus: String? = null,
    @SerialName("billingPeriodStart")
    val billingPeriodStart: String? = null,
    @SerialName("billingPeriodEnd")
    val billingPeriodEnd: String? = null,
)

@Serializable
data class LettaQuotaDetail(
    val tier: String? = null,
    @SerialName("percentUsed")
    val percentUsed: Double? = null,
    val used: Int? = null,
    val limit: Int? = null,
    @SerialName("isLow")
    val isLow: Boolean? = null,
)
