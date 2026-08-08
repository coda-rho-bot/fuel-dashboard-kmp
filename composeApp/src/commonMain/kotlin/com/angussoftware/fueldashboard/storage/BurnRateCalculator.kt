package com.angussoftware.fueldashboard.storage

import com.angussoftware.fueldashboard.model.FuelSnapshot
import kotlin.math.max

/**
 * Simple burn-rate calculator using linear regression.
 *
 * Takes the slope of `tokensUsedPct` over time. A positive slope means fuel is being consumed.
 *
 * MVP: simple OLS linear regression. Holt's Linear is a future enhancement.
 */
object BurnRateCalculator {

    /**
     * @param snapshots Historical fuel snapshots (oldest first).
     * @return Burn rate in pct/hour, or null if insufficient data (< 3 points).
     */
    fun compute(snapshots: List<FuelSnapshot>): Double? {
        if (snapshots.size < 3) return null

        // x = time in hours since first snapshot, y = tokensUsedPct
        val firstTs = snapshots.first().timestampMs
        val xs = snapshots.map { (it.timestampMs - firstTs).toDouble() / 3_600_000.0 }
        val ys = snapshots.map { it.tokensUsedPct.toDouble() }

        val n = xs.size
        val xMean = xs.average()
        val yMean = ys.average()

        var numerator = 0.0
        var denominator = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - xMean
            numerator += dx * (ys[i] - yMean)
            denominator += dx * dx
        }

        if (denominator == 0.0) return null

        val slope = numerator / denominator // pct per hour
        return max(0.0, slope) // only positive burn rates
    }
}
