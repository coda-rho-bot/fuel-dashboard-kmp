package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.presentation.DashboardState
import com.angussoftware.fueldashboard.util.epochMillis

/**
 * Platform-neutral status summary for persistent surfaces: the Android
 * ongoing notification and the desktop HUD mini-window (future: iOS Live
 * Activity — issue #60).
 *
 * Derivation lives here so every surface shows identical numbers from the
 * same [DashboardState].
 */
data class FuelStatusModel(
    /** Most critical provider — the collapsed-notification headline. */
    val headline: Headline?,
    /** One entry per report-bearing provider, in display order. */
    val quotaLines: List<QuotaLine>,
    /** Credit pools (Letta credits, Junie balance) when known. */
    val creditLines: List<CreditLine>,
    val lastUpdated: Long,
) {
    data class Headline(
        val name: String,
        val remainingPct: Int?,
        val resetsAt: Long?,
    )

    data class QuotaLine(
        val name: String,
        val remainingPct: Int?,
        val resetsAt: Long?,
        val available: Boolean,
        /** Window duration in hours — used for timer gauge bars. */
        val windowHours: Double = 0.0,
    ) {
        /**
         * Time remaining as a percentage of the total window, for timer
         * gauge bars. 100 = window just started, 0 = window about to reset.
         */
        fun timeRemainingPct(now: Long = epochMillis()): Int? {
            if (resetsAt == null || windowHours <= 0.0) return null
            val windowMs = (windowHours * 3_600_000).toLong()
            if (windowMs <= 0) return null
            val remaining = resetsAt - now
            if (remaining <= 0) return 0
            return ((remaining.toDouble() / windowMs) * 100).toInt().coerceIn(0, 100)
        }
    }

    data class CreditLine(
        val name: String,
        val creditsTotal: Int?,
        val creditsUsed: Int?,
        val junieBalance: Double? = null,
    )

    val hasAnyData: Boolean get() = headline != null || creditLines.isNotEmpty()

    companion object {
        fun from(state: DashboardState): FuelStatusModel {
            val reports = state.providerReports.values
                .filter { it.available }
                .sortedBy { it.displayName.lowercase() }

            // Windowed quotas only — credit-only providers have no %/reset and
            // are represented in creditLines instead (no duplicate "—" rows).
            val quotaLines = reports
                .filter { it.remainingPct != null }
                .map { r ->
                    QuotaLine(
                        name = r.displayName,
                        remainingPct = r.remainingPct,
                        resetsAt = r.resetsAt,
                        available = r.available,
                        windowHours = r.windowHours,
                    )
                }

            // Headline: the lowest remaining percentage; providers without a
            // percentage (credit pools) never headline — they're in creditLines.
            val headline = reports
                .filter { it.remainingPct != null }
                .minByOrNull { it.remainingPct!! }
                ?.let { Headline(it.displayName, it.remainingPct, it.resetsAt) }

            val creditLines = buildList {
                reports.firstOrNull { it.creditsTotal != null }?.let { r ->
                    add(
                        CreditLine(
                            name = r.displayName,
                            creditsTotal = r.creditsTotal,
                            creditsUsed = r.creditsUsed,
                        ),
                    )
                }
                state.junieBalance?.let { bal ->
                    add(CreditLine(name = "Junie", creditsTotal = null, creditsUsed = null, junieBalance = bal))
                }
            }

            return FuelStatusModel(
                headline = headline,
                quotaLines = quotaLines,
                creditLines = creditLines,
                lastUpdated = state.lastUpdated,
            )
        }

        /**
         * "2h 15m", "45m", "3d 4h", "…" — countdown for notification lines.
         */
        fun formatCountdown(resetsAt: Long?, now: Long = epochMillis()): String? {
            if (resetsAt == null) return null
            val ms = resetsAt - now
            if (ms <= 0) return "resetting"
            val minutes = ms / 60_000
            val d = minutes / (24 * 60)
            val h = (minutes % (24 * 60)) / 60
            val m = minutes % 60
            return when {
                d > 0 -> "${d}d ${h}h"
                h > 0 -> "${h}h ${m}m"
                else -> "${m}m"
            }
        }
    }
}
