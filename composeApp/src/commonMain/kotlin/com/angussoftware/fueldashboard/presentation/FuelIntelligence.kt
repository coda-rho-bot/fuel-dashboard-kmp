package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.database.FuelSnapshotRecord
import com.angussoftware.fueldashboard.database.UsageRecord

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
    )

    /** Daily rollup of expired-quota waste for one provider. */
    data class DailyWaste(
        /** Local-day start (epoch ms). */
        val dayStart: Long,
        /** Observed windows this day. */
        val windows: Int,
        /** Average remaining-at-expiry across observed windows (0-100). */
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
     * each window expired. The window length comes from each provider's own
     * quota mechanics (snapshot metadata: z.ai ~5h sliding, Letta daily 24h,
     * credit pools = refill period) — not a hardcoded value.
     *
     * Sampling the gauge at each window boundary yields adjacent
     * non-overlapping windows, and the level at the boundary is exactly the
     * quota that expired unused:
     *
     *   - window unused entirely          → 100% wasted
     *   - expired with 10% still left     → 10% wasted
     *   - exhausted to 0% before the end  → 0% wasted
     */
    fun providerWaste(
        snapshots: List<com.angussoftware.fueldashboard.database.ProviderFuelSnapshot>,
        since: Long,
        now: Long,
    ): List<ProviderWaste> {
        return snapshots
            .groupBy { it.providerId }
            .mapNotNull { (providerId, rows) ->
                val name = rows.firstOrNull()?.providerName ?: providerId
                // Median window length from the provider's own metadata
                val windowMs = rows.mapNotNull { it.windowHours }
                    .filter { it > 0 }
                    .sorted()
                    .let { if (it.isEmpty()) null else it[it.size / 2] * 3_600_000.0 }
                    ?.toLong() ?: return@mapNotNull null

                val tiles = wasteTiles(rows, windowMs, since, now)
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

    /** Boundary-sampled tiles for one provider (remaining % at each window expiry). */
    private fun wasteTiles(
        rows: List<com.angussoftware.fueldashboard.database.ProviderFuelSnapshot>,
        windowMs: Long,
        since: Long,
        now: Long,
    ): List<WasteTile> {
        val withPct = rows.mapNotNull { s -> s.remainingPct?.let { s.timestamp to it } }
            .sortedBy { it.first }
        if (withPct.isEmpty()) return emptyList()

        val tolerance = (windowMs / 10).coerceIn(15 * 60_000L, 60 * 60_000L)
        val tiles = mutableListOf<WasteTile>()
        // Anchor the grid to the data, not the epoch: provider windows are not
        // wall-clock aligned (z.ai slides per-user). The first window expires
        // one window-length after observation begins.
        var boundary = withPct.first().first + windowMs
        var cursor = 0
        while (boundary <= now) {
            var best: Pair<Long, Double>? = null
            while (cursor < withPct.size && withPct[cursor].first <= boundary + tolerance) {
                val cand = withPct[cursor]
                if (cand.first >= boundary - tolerance) {
                    if (best == null || kotlin.math.abs(cand.first - boundary) < kotlin.math.abs(best.first - boundary)) {
                        best = cand
                    }
                }
                if (cand.first > boundary) break
                cursor++
            }
            if (best != null) tiles.add(WasteTile(windowEnd = boundary, wastedPct = best.second))
            boundary += windowMs
        }
        return tiles
    }

    /** Rolls observed tiles into local-day averages. */
    fun dailyWaste(tiles: List<WasteTile>): List<DailyWaste> {
        return tiles
            .groupBy { it.windowEnd / 86_400_000 }
            .map { (dayKey, dayTiles) ->
                DailyWaste(
                    dayStart = dayKey * 86_400_000,
                    windows = dayTiles.size,
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
                            append("Fuel dropped ${"%.1f".format(burstTotal)}%")
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
