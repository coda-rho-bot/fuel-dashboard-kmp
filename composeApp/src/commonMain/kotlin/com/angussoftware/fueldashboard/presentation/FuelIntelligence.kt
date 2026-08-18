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

    /** Ingestion lag tolerance for waste attribution (records arrive after drops). */
    const val METERED_LAG_MS = 10 * 60_000L

    /** One hour bucket of fuel consumption vs. metered activity. */
    data class WasteWindow(
        val hourStart: Long,
        val fuelConsumedPct: Double,
        val meteredTokens: Long,
        val avgActiveAgents: Double,
        val unattributed: Boolean,
    )

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
     * Hourly waste windows over the last [hours] hours.
     *
     * Fuel consumption per hour = sum of negative tokensPct deltas between
     * consecutive snapshots (the 5h sliding window also rises as old usage
     * expires — only drops are consumption). A window is "unattributed"
     * when it consumed >= [dropThresholdPct] but metered usage recorded
     * less than [meteredFloorTokens] tokens in that hour.
     */
    fun wasteWindows(
        snapshots: List<FuelSnapshotRecord>,
        usage: List<UsageRecord>,
        hours: Int = 24,
        dropThresholdPct: Double = 1.0,
        meteredFloorTokens: Long = 1_000,
    ): List<WasteWindow> {
        val hourMs = 3_600_000L
        val newest = maxOf(
            snapshots.maxOfOrNull { it.timestamp } ?: return emptyList(),
            usage.maxOfOrNull { it.timestamp } ?: 0L,
        )
        val windowStart = newest - hours * hourMs

        // Sum consumption (negative deltas only) + agent counts per hour bucket.
        val consumption = HashMap<Long, Double>()
        val agentSamples = HashMap<Long, MutableList<Int>>()
        val sorted = snapshots.filter { it.timestamp >= windowStart }.sortedBy { it.timestamp }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            val prevPct = prev.tokensPct ?: continue
            val curPct = cur.tokensPct ?: continue
            val delta = curPct - prevPct
            val bucket = cur.timestamp / hourMs * hourMs
            if (delta < 0) {
                consumption[bucket] = (consumption[bucket] ?: 0.0) + (-delta)
            }
            agentSamples.getOrPut(bucket) { mutableListOf() }.add(cur.activeAgentCount)
        }

        // Metered tokens per hour bucket. Attribution tolerance: usage records
        // arrive on the ingestion cadence (30s-5min) AFTER the fuel drop they
        // caused and can cross into the NEXT hour bucket (drop at :55, record
        // at :03). A window therefore counts tokens from its own bucket AND
        // the following one — over-attribution is harmless, phantom
        // "unattributed waste" from lag is not.
        val metered = HashMap<Long, Long>()
        for (u in usage.filter { it.timestamp >= windowStart - hourMs }) {
            val bucket = u.timestamp / hourMs * hourMs
            metered[bucket] = (metered[bucket] ?: 0L) + u.inputTokens + u.outputTokens
        }
        fun meteredNear(bucket: Long): Long =
            (metered[bucket] ?: 0L) + (metered[bucket + hourMs] ?: 0L)

        return consumption.keys.sorted().map { bucket ->
            val consumed = consumption[bucket] ?: 0.0
            val tokens = meteredNear(bucket)
            val agents = agentSamples[bucket]?.average() ?: 0.0
            WasteWindow(
                hourStart = bucket,
                fuelConsumedPct = consumed,
                meteredTokens = tokens,
                avgActiveAgents = agents,
                unattributed = consumed >= dropThresholdPct && tokens < meteredFloorTokens,
            )
        }
    }

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
