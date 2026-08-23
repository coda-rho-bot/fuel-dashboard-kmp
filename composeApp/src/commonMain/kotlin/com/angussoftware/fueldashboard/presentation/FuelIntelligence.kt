package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.database.FuelSnapshotRecord
import kotlinx.datetime.toLocalDateTime
import com.angussoftware.fueldashboard.database.UsageRecord
import com.angussoftware.fueldashboard.util.formatRoot

/**
 * Pure computations for the Fuel Intelligence tab (roadmap Phase 4).
 *
 * Two derivations, both from data that already exists — no new tables:
 *
 *  1. Waste detection — hourly windows where the fuel gauge dropped but
 *     metered usage shows (nearly) nothing. Those drops are unattributed:
 *     idle polling, restart storms, or consumption the metering doesn't
 *     see yet.
 *  2. Fuel event history — a merged, deduplicated timeline of significant
 *     drops, agent model switches, and recommendation changes.
 */
object FuelIntelligence {

    /** One quota window for a provider: what remained when it expired. */
    data class WasteTile(
        /** End of the window (epoch ms) — provider-specific length. */
        val windowEnd: Long,
        /** Fuel level (remaining %) at expiry — this quota evaporated unused. */
        val wastedPct: Double,
        /** False = measured from the gauge at expiry; true = reconstructed
         *  from metered usage (dashboard was down at expiry). */
        val estimated: Boolean = false,
    )

    /** Daily rollup of expired-quota waste for one provider. */
    data class DailyWaste(
        /** Local-day start (epoch ms). */
        val dayStart: Long,
        /** Total windows this day (observed + estimated). */
        val windows: Int,
        /** Windows measured directly from the gauge. */
        val observed: Int,
        /** Windows reconstructed from metered usage (dashboard down at expiry). */
        val estimated: Int,
        /** Average remaining-at-expiry across windows (0-100). */
        val wastedPctAvg: Double,
        /** True if any window exhausted (nothing wasted). */
        val anyExhausted: Boolean,
    )

    /** Expired-quota waste for one provider, using that provider's own window length. */
    data class ProviderWaste(
        val providerId: String,
        val providerName: String,
        /** The provider's quota window length in ms (from its own snapshot metadata). */
        val windowMs: Long,
        val daily: List<DailyWaste>,
        /** Average wasted % across all observed days. */
        val wastedPctAvg: Double,
    )

    /**
     * Expired-quota waste per provider: how much quota evaporated unused when
     * each window expired. Window length comes from each provider's own quota
     * mechanics. Two tiling strategies:
     *
     *  - FIXED-RESET providers (Letta daily/4h, monthly pools): windows reset at
     *    known times. Boundaries are the ACTUAL reset times observed in the
     *    snapshots' resetAt transitions — the last remaining-value before each
     *    reset is the waste. (Grid tiling would sample mid-window and overcount.)
     *  - SLIDING providers (z.ai 5h): the window slides continuously; the API
     *    value at any instant is the trailing-window usage, so sampling every
     *    windowMs yields adjacent, exact tiles. Detected via resetAt changing
     *    on nearly every poll.
     *
     *   - window unused entirely          -> 100% wasted
     *   - expired with 10% still left     -> 10% wasted
     *   - exhausted to 0% before the end  -> 0% wasted
     */
    fun providerWaste(
        snapshots: List<com.angussoftware.fueldashboard.database.ProviderFuelSnapshot>,
        usage: List<UsageRecord> = emptyList(),
        since: Long,
        now: Long,
        /** The provider whose quota the metered usage actually burns (z.ai via
         *  BYOK). Gap reconstruction is only valid for that provider — other
         *  providers' windows must not be reconstructed with foreign tokens. */
        usageOwnerProviderId: String? = null,
    ): List<ProviderWaste> {
        return snapshots
            .groupBy { it.providerId }
            .mapNotNull { (providerId, rows) ->
                val name = rows.firstOrNull()?.providerName ?: providerId
                val windowMs = rows.mapNotNull { it.windowHours }
                    .filter { it > 0 }
                    .sorted()
                    .let { if (it.isEmpty()) null else it[it.size / 2] * 3_600_000.0 }
                    ?.toLong() ?: return@mapNotNull null

                val sorted = rows.sortedBy { it.timestamp }
                val measured = if (isFixedReset(sorted, windowMs)) {
                    resetDrivenTiles(sorted)
                } else {
                    gridTiles(sorted, windowMs, now)
                }
                val tiles = if (usageOwnerProviderId == null || providerId == usageOwnerProviderId) {
                    reconstructGaps(measured, usage, windowMs, sorted.first().timestamp, now)
                } else {
                    measured
                }
                if (tiles.isEmpty()) return@mapNotNull null
                val daily = dailyWaste(tiles)
                if (daily.isEmpty()) return@mapNotNull null
                ProviderWaste(
                    providerId = providerId,
                    providerName = name,
                    windowMs = windowMs,
                    daily = daily,
                    wastedPctAvg = daily.map { it.wastedPctAvg }.average(),
                )
            }
            .sortedByDescending { it.wastedPctAvg }
    }

    /**
     * Fixed-reset detection: a sliding window's resetAt moves on ~every poll
     * (distinct count ~ snapshot count); a fixed window's resetAt changes only
     * at actual resets (~ window count). If distinct resets are sparse relative
     * to the expected window count, resets are discrete events.
     */
    private fun isFixedReset(
        sorted: List<com.angussoftware.fueldashboard.database.ProviderFuelSnapshot>,
        windowMs: Long,
    ): Boolean {
        val resets = sorted.mapNotNull { it.resetAt }.distinct()
        if (resets.size < 2) return false
        val span = (sorted.last().timestamp - sorted.first().timestamp).toDouble()
        val expectedWindows = (span / windowMs).coerceAtLeast(1.0)
        return resets.size <= expectedWindows * 4 + 2
    }

    /** Tiles at observed reset transitions: the last remaining value before each reset. */
    private fun resetDrivenTiles(
        sorted: List<com.angussoftware.fueldashboard.database.ProviderFuelSnapshot>,
    ): List<WasteTile> {
        val tiles = mutableListOf<WasteTile>()
        var prev: com.angussoftware.fueldashboard.database.ProviderFuelSnapshot? = null
        for (snap in sorted) {
            val pre = prev
            prev = snap
            val cur = snap.resetAt ?: continue
            val before = pre?.resetAt
            if (before != null && cur > before && pre.remainingPct != null) {
                tiles.add(WasteTile(windowEnd = before, wastedPct = pre.remainingPct!!))
            }
        }
        return tiles
    }

    /** Boundary-sampled tiles for sliding windows (adjacent, exact tiling). */
    private fun gridTiles(
        sorted: List<com.angussoftware.fueldashboard.database.ProviderFuelSnapshot>,
        windowMs: Long,
        now: Long,
    ): List<WasteTile> {
        val withPct = sorted.mapNotNull { s -> s.remainingPct?.let { s.timestamp to it } }
        if (withPct.isEmpty()) return emptyList()

        val tolerance = (windowMs / 10).coerceIn(15 * 60_000L, 60 * 60_000L)
        val tiles = mutableListOf<WasteTile>()
        var boundary = withPct.first().first + windowMs
        var cursor = 0
        while (boundary <= now) {
            // Remaining AT expiry: prefer the LATEST snapshot at-or-before the
            // boundary (post-boundary snapshots reflect the next window).
            var best: Pair<Long, Double>? = null
            while (cursor < withPct.size && withPct[cursor].first <= boundary) {
                val cand = withPct[cursor]
                if (cand.first >= boundary - tolerance) best = cand
                cursor++
            }
            if (best != null) tiles.add(WasteTile(windowEnd = boundary, wastedPct = best.second))
            boundary += windowMs
        }
        return tiles
    }

    /**
     * Reconstructs waste for windows the dashboard was down to observe, from
     * metered usage: used% = tokens-in-window / capacity, capacity calibrated
     * from windows we DID observe (tokens <-> gauge-% correspondence).
     * Requires >=2 calibration points; otherwise missing windows stay missing
     * — never fabricate without basis. Reconstructed tiles are marked estimated.
     * Boundaries derive from measured windowEnds (aligned with the provider's
     * actual reset sequence), never a synthetic grid — a misanchored grid
     * fabricates phantom tiles beside already-measured windows.
     */
    private fun reconstructGaps(
        measured: List<WasteTile>,
        usage: List<UsageRecord>,
        windowMs: Long,
        dataStart: Long,
        now: Long,
    ): List<WasteTile> {
        if (usage.isEmpty() || measured.size < 2) return measured

        // Calibrate: tokens per used-% from observed tiles
        val calibrations = measured.mapNotNull { tile ->
            val tokens = usage.filter { it.timestamp in (tile.windowEnd - windowMs)..tile.windowEnd }
                .sumOf { it.inputTokens + it.outputTokens }
            val usedPct = 100.0 - tile.wastedPct
            if (usedPct >= 5.0 && tokens > 0) tokens / usedPct else null
        }
        if (calibrations.size < 2) return measured
        val perPct = calibrations.sorted()[calibrations.size / 2] // median tokens per 1%

        // Boundaries are derived from the MEASURED tiles' windowEnds, never a
        // synthetic grid: fixed-reset ends sit at actual resetAt times that no
        // dataStart-anchored grid can match, so grid boundaries would fall
        // beside measured windows and fabricate phantom duplicates. We fill
        // the gaps between consecutive measured ends (dashboard-down windows),
        // extend the tail to `now`, and extend the head back to `dataStart`.
        // The half-window guard keeps slightly-drifting reset times from
        // spawning near-duplicate tiles next to a measured end.
        val ends = measured.map { it.windowEnd }.sorted()
        val have = ends.toHashSet()
        val half = windowMs / 2
        val boundaries = mutableListOf<Long>()
        for (i in 0 until ends.size - 1) {
            var b = ends[i] + windowMs
            while (ends[i + 1] - b > half) {
                boundaries.add(b)
                b += windowMs
            }
        }
        var b = ends.last() + windowMs
        while (b <= now) {
            boundaries.add(b)
            b += windowMs
        }
        b = ends.first() - windowMs
        while (b > dataStart) {
            boundaries.add(b)
            b -= windowMs
        }

        val reconstructed = boundaries.filter { it !in have }.map { boundary ->
            val tokens = usage.filter { it.timestamp in (boundary - windowMs)..boundary }
                .sumOf { it.inputTokens + it.outputTokens }
            val usedPct = (tokens / perPct).coerceIn(0.0, 100.0)
            WasteTile(windowEnd = boundary, wastedPct = 100.0 - usedPct, estimated = true)
        }
        return (measured + reconstructed).sortedBy { it.windowEnd }
    }

    /** Rolls observed tiles into local-day averages. */
    fun dailyWaste(tiles: List<WasteTile>): List<DailyWaste> {
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        return tiles
            .groupBy { kotlinx.datetime.Instant.fromEpochMilliseconds(it.windowEnd).toLocalDateTime(tz).date.toEpochDays().toLong() }
            .map { (dayKey, dayTiles) ->
                DailyWaste(
                    dayStart = dayKey * 86_400_000,
                    windows = dayTiles.size,
                    observed = dayTiles.count { !it.estimated },
                    estimated = dayTiles.count { it.estimated },
                    wastedPctAvg = dayTiles.map { it.wastedPct }.average(),
                    anyExhausted = dayTiles.any { it.wastedPct <= 1.0 },
                )
            }
            .sortedByDescending { it.dayStart }
    }

    enum class FuelEventType { FUEL_DROP, MODEL_SWITCH, RECOMMENDATION }

    data class FuelEvent(
        val timestamp: Long,
        val type: FuelEventType,
        val description: String,
    )

    /** Simple model-history row as needed for switch detection. */
    data class AgentModelPeriod(
        val agentName: String,
        val model: String,
        val validFrom: Long,
        val validTo: Long?,
    )

    /** Simple decision row as needed for the timeline. */
    data class DecisionRecord(
        val timestamp: Long,
        val modelHandle: String,
        val reason: String,
    )

    
    /**
     * Merged fuel-event timeline, newest first.
     *
     *  - FUEL_DROP: consecutive-snapshot drops >= [dropThresholdPct];
     *    drops within [aggregateMs] merge into one event (restart storms
     *    show as a burst of poll-level drops, not 40 separate events).
     *  - MODEL_SWITCH: consecutive per-agent model periods with different models.
     *  - RECOMMENDATION: deduplicated recommender decisions.
     */
    fun fuelEvents(
        snapshots: List<FuelSnapshotRecord>,
        modelPeriods: List<AgentModelPeriod>,
        decisions: List<DecisionRecord>,
        dropThresholdPct: Double = 1.0,
        aggregateMs: Long = 10 * 60_000,
        limit: Int = 50,
    ): List<FuelEvent> {
        val events = mutableListOf<FuelEvent>()

        // --- Significant fuel drops (with burst aggregation) ---
        val sorted = snapshots.sortedBy { it.timestamp }
        var burstTotal = 0.0
        var burstStart = -1L
        var burstEnd = -1L
        var burstAgents = 0
        var burstModels = mutableSetOf<String>()

        fun flushBurst() {
            if (burstTotal >= dropThresholdPct && burstStart > 0) {
                val models = burstModels.filter { it.isNotBlank() }.joinToString(", ")
                events.add(
                    FuelEvent(
                        timestamp = burstEnd,
                        type = FuelEventType.FUEL_DROP,
                        description = buildString {
                            append("Fuel dropped ${formatRoot("%.1f", burstTotal)}%")
                            append(" · $burstAgents active agent${if (burstAgents == 1) "" else "s"}")
                            if (models.isNotEmpty()) append(" · $models")
                        },
                    ),
                )
            }
            burstTotal = 0.0
            burstStart = -1L
            burstEnd = -1L
            burstAgents = 0
            burstModels = mutableSetOf()
        }

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            val prevPct = prev.tokensPct ?: continue
            val curPct = cur.tokensPct ?: continue
            val drop = prevPct - curPct
            if (drop <= 0) {
                flushBurst()
                continue
            }
            if (burstStart > 0 && cur.timestamp - burstEnd > aggregateMs) {
                flushBurst()
            }
            if (burstStart <= 0) {
                burstStart = prev.timestamp
                burstAgents = cur.activeAgentCount
                burstModels = (cur.activeModels ?: "").split(",").map { it.trim() }.toMutableSet()
            }
            burstTotal += drop
            burstEnd = cur.timestamp
        }
        flushBurst()

        // --- Model switches ---
        val byAgent = modelPeriods.sortedWith(compareBy({ it.agentName }, { it.validFrom }))
            .groupBy { it.agentName }
        for ((_, periods) in byAgent) {
            for (i in 1 until periods.size) {
                val from = periods[i - 1]
                val to = periods[i]
                if (from.model != to.model) {
                    events.add(
                        FuelEvent(
                            timestamp = to.validFrom,
                            type = FuelEventType.MODEL_SWITCH,
                            description = "${to.agentName}: ${from.model} → ${to.model}",
                        ),
                    )
                }
            }
        }

        // --- Recommendation changes ---
        for (d in decisions) {
            events.add(
                FuelEvent(
                    timestamp = d.timestamp,
                    type = FuelEventType.RECOMMENDATION,
                    description = buildString {
                        append("Recommended ${d.modelHandle}")
                        if (d.reason.isNotBlank()) append(" — ${d.reason}")
                    },
                ),
            )
        }

        return events.sortedByDescending { it.timestamp }.take(limit)
    }
}
