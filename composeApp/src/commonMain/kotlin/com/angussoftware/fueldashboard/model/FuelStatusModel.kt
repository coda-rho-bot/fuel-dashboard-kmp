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
    /** One entry per report-bearing provider, in display order. */
    val quotaLines: List<QuotaLine>,
    /** Credit pools (Letta credits, Junie balance) when known. */
    val creditLines: List<CreditLine>,
    val lastUpdated: Long,
) {
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

    val hasAnyData: Boolean get() = quotaLines.isNotEmpty() || creditLines.isNotEmpty()

    /**
     * Collapsed-notification body text: all quota providers joined with
     * separators, plus credit-total and Junie-balance extras. Pure
     * function over the model — rendered by the Android notification
     * and unit-tested on desktop (status-surface renderer).
     */
    fun collapsedBodyText(loadingText: String): String {
        if (!hasAnyData) return loadingText
        return buildString {
            for ((i, line) in quotaLines.withIndex()) {
                if (i > 0) append("  ·  ")
                val pct = line.remainingPct?.let { "$it%" } ?: "—"
                val cd = formatCountdown(line.resetsAt)
                append(if (cd != null) "${line.name} $pct · $cd" else "${line.name} $pct")
            }
            creditLines.firstOrNull { it.creditsTotal != null }?.let {
                append("  ·  ${it.name} ${it.creditsTotal} cr")
            }
            creditLines.firstOrNull { it.junieBalance != null }?.let {
                append("  ·  ${it.name} $${com.angussoftware.fueldashboard.util.formatRoot("%.2f", it.junieBalance!!)}")
            }
        }.ifEmpty { loadingText }
    }

    companion object {
        fun from(state: DashboardState): FuelStatusModel {
            // Order by the user's provider order (Settings list); reports for
            // providers missing from settings (e.g. removed mid-flight) keep
            // alphabetical fallback. Ordering flows to HUD + notification rows.
            val orderIndex = state.settings.providers
                .mapIndexed { i, p -> p.id to i }.toMap()
            val reports = state.providerReports.values
                .filter { it.available }
                .sortedWith(
                    compareBy(
                        { orderIndex[it.providerId] ?: Int.MAX_VALUE },
                        { it.displayName.lowercase() },
                    ),
                )

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
