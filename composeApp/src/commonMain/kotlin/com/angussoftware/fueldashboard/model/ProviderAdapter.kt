package com.angussoftware.fueldashboard.model

/**
 * Type of fuel/quota model a provider uses.
 *
 * - [WINDOW_CREDIT]: Refilling tank that depletes during use and refills on a timer (z.ai, Letta Cloud).
 * - [SPEND_BUDGET]: Monthly dollar budget (OpenAI, Anthropic).
 * - [RATE_LIMIT]: Requests-per-minute throttle (OpenAI, Anthropic).
 */
enum class ProviderType {
    WINDOW_CREDIT,
    SPEND_BUDGET,
    RATE_LIMIT,
}

/**
 * Immutable report returned by a [ProviderAdapter] after polling its provider's API.
 *
 * Designed to accommodate all [ProviderType]s:
 * - [WINDOW_CREDIT]: uses [remainingPct], [resetsAt], [windowHours]
 * - [SPEND_BUDGET]: uses [usedDollars], [limitDollars]
 * - [RATE_LIMIT]: uses [remainingPct] (percentage of rate budget remaining)
 */
data class ProviderReport(
    val providerId: String,
    val displayName: String,
    val type: ProviderType,
    val remainingPct: Int? = null,
    val resetsAt: Long? = null,
    val windowHours: Double = 0.0,
    val usedDollars: Double? = null,
    val limitDollars: Double? = null,
    val available: Boolean = true,
    val windows: List<ReportWindow> = emptyList(),
    val rawDisplay: String = "",
)

/**
 * A single quota window within a provider report.
 * Some providers (z.ai, Letta Cloud) have multiple windows (e.g. 5h token + session).
 */
data class ReportWindow(
    val name: String,
    val remainingPct: Int?,
    val resetsAt: Long?,
    val windowHours: Double,
)

/**
 * One adapter per provider. Each adapter knows how to authenticate and poll its specific API.
 *
 * The ViewModel manages a list of active [ProviderAdapter]s and polls them all.
 * The dashboard renders one section per adapter.
 */
interface ProviderAdapter {
    val providerId: String
    val displayName: String
    val providerType: ProviderType

    /**
     * Polls the provider's API and returns a [ProviderReport].
     * Throws on network/auth errors — the caller handles exceptions.
     */
    suspend fun poll(): ProviderReport

    /** Releases HTTP client resources. */
    fun close()
}
