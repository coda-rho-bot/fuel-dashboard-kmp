package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.database.FuelSnapshotRecord
import com.angussoftware.fueldashboard.database.UsageRecord
import kotlinx.datetime.toLocalDateTime

/**
 * Fuel Advisor v3 — regime-aware fuel advice.
 *
 * Replaces the v2 recommender's blind "switch everything to the cheapest
 * model" logic. The v2 flaw: it maximized token count per credit without
 * considering quota history, reset timing, or whether the cheap model is
 * smart enough for the work. v3 answers the actual questions:
 *
 *   1. Should anything change at all? (quota regime from exhaustion history —
 *      if the user rarely burns out their quota, downgrading is pointless:
 *      surplus is wasted either way)
 *   2. Is THIS window at risk? (projection: burn rate × time-to-reset)
 *   3. What can safely switch? (routine work only — conversations that run
 *      repeatedly across days; interactive sessions keep the smart model)
 *
 * Pure functions over metered data — no I/O, fully testable.
 */
object FuelAdvisor {

    /** How quota has actually been used across recent windows. */
    data class QuotaRegime(
        val windowsAnalyzed: Int,
        /** Windows that hit >= [EXHAUSTION_THRESHOLD_PCT] in the analysis span. */
        val exhaustions: Int,
        /** Projected TOKENS_PCT at reset for the current window (0-100). */
        val projectedPctAtReset: Double?,
        /** Current burn rate, pct/hour. */
        val burnPctPerHr: Double?,
        /** ms until the current window resets. */
        val resetInMs: Long?,
    )

    /** Repeated work (cron-like): safe to run on a cheaper model. */
    data class RoutineConsumer(
        val conversationKey: String,
        val title: String?,
        val agentName: String,
        val model: String,
        /** Distinct days this conversation appeared on (>= ROUTINE_MIN_DAYS = routine). */
        val activeDays: Int,
        val tokensPerDay: Long,
        /** Share of this consumer's cost that would be saved on the cheap model (0..1). */
        val savingsFraction: Double,
        val currentCreditPerDay: Double,
        val projectedCreditPerDay: Double,
    )

    /**
     * Advice states. Only AT_RISK and PERSISTENT_PRESSURE recommend action,
     * and only for routine work — interactive sessions are never downgraded.
     */
    sealed interface Advice {
        /** Not enough history to judge. Honest about it. */
        data object InsufficientData : Advice

        /** Quota rarely exhausts — downgrading anything is pointless. */
        data class Surplus(
            val regime: QuotaRegime,
        ) : Advice

        /** Healthy window: projected to finish with headroom. No action. */
        data class Healthy(
            val regime: QuotaRegime,
            val projectedHeadroomPct: Double,
        ) : Advice

        /** This window will exhaust before reset — switch ROUTINE work now. */
        data class AtRisk(
            val regime: QuotaRegime,
            val projectedExhaustInMs: Long?,
            val routineConsumers: List<RoutineConsumer>,
        ) : Advice

        /** Quota exhausts repeatedly — standing advice to move routine work. */
        data class PersistentPressure(
            val regime: QuotaRegime,
            val routineConsumers: List<RoutineConsumer>,
        ) : Advice
    }

    // ── Tunables ────────────────────────────────────────────────────────

    /** A window "exhausts" when REMAINING pct falls to/below this level.
     *  tokensPct in fuel_snapshots is REMAINING % (0 = exhausted, 100 = full). */
    const val EXHAUSTION_THRESHOLD_PCT = 5.0

    /** >= this many exhaustions in the analysis span = persistent pressure. */
    const val PRESSURE_EXHAUSTION_COUNT = 4

    /** A conversation seen on >= this many distinct days is "routine". */
    const val ROUTINE_MIN_DAYS = 3

    /** Assume this many pct/hour when burn rate is unknown (conservative). */
    const val DEFAULT_BURN_PCT_PER_HR = 8.0

    // ── Regime analysis ────────────────────────────────────────────────

    /**
     * Analyzes quota history: 5h windows are sliding, so we approximate
     * exhaustion count by peaks — runs of consecutive snapshots >= threshold
     * count as one exhaustion event (clustered within [EXHAUSTION_CLUSTER_MS]).
     */
    fun quotaRegime(
        snapshots: List<FuelSnapshotRecord>,
        now: Long,
        resetAt: Long?,
    ): QuotaRegime {
        val withPct = snapshots.mapNotNull { s -> s.tokensPct?.let { s.timestamp to it } }
            .sortedBy { it.first }

        // Exhaustion events: remaining falls to/below threshold. Clustering keys
        // on RECOVERY above the threshold (a poll gap within an exhausted
        // plateau must not split one event into many), with a time cap.
        var exhaustions = 0
        var inExhaustion = false
        for ((_, pct) in withPct) {
            val exhausted = pct <= EXHAUSTION_THRESHOLD_PCT
            if (exhausted && !inExhaustion) exhaustions++
            inExhaustion = exhausted
        }

        // Burn rate from the last 2h of data
        val burn = burnRate(withPct, now)

        // Projection: remaining FALLS by burn × hours-to-reset
        // (tokensPct is REMAINING % — consumption erodes it toward 0)
        val current = withPct.lastOrNull()?.second
        val resetIn = resetAt?.let { it - now }?.takeIf { it > 0 }
        val projected = if (current != null && burn != null && resetIn != null) {
            (current - burn * (resetIn / 3_600_000.0)).coerceIn(0.0, 100.0)
        } else {
            null
        }

        // Windows analyzed ≈ span / 5h (approximate — sliding windows)
        val spanMs = (withPct.lastOrNull()?.first ?: now) - (withPct.firstOrNull()?.first ?: now)
        val windows = (spanMs / (5 * 3_600_000.0)).toInt().coerceAtLeast(1)

        return QuotaRegime(
            windowsAnalyzed = windows,
            exhaustions = exhaustions,
            projectedPctAtReset = projected,
            burnPctPerHr = burn,
            resetInMs = resetIn,
        )
    }

    /** Average negative gauge delta per hour over the recent span (pct/hour). */
    private fun burnRate(withPct: List<Pair<Long, Double>>, now: Long): Double? {
        val recent = withPct.filter { it.first >= now - 2 * 3_600_000 }
        if (recent.size < 3) return null
        var consumed = 0.0
        for (i in 1 until recent.size) {
            val d = recent[i].second - recent[i - 1].second
            if (d < 0) consumed += -d
        }
        val hours = (recent.last().first - recent.first().first) / 3_600_000.0
        return if (hours > 0.1) consumed / hours else null
    }

    // ── Routine-work classification ────────────────────────────────────

    /**
     * Classifies conversations as routine (seen on >= [ROUTINE_MIN_DAYS]
     * distinct days) and projects their cost on the cheapest known model.
     * Interactive sessions are excluded — never recommend downgrading them.
     */
    fun routineConsumers(
        usage: List<UsageRecord>,
        now: Long,
        spanDays: Int = 7,
    ): List<RoutineConsumer> {
        val since = now - spanDays * 24 * 3_600_000
        val byConversation = usage.filter { it.timestamp >= since && it.conversationId != null }
            .groupBy { it.conversationId!! }

        val results = byConversation.mapNotNull { (convId, records) ->
            val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
            val days = records.map {
                kotlinx.datetime.Instant.fromEpochMilliseconds(it.timestamp).toLocalDateTime(tz).date.toEpochDays()
            }.distinct().size
            if (days < ROUTINE_MIN_DAYS) return@mapNotNull null

            // Dominant model = the one carrying most tokens
            val byModel = records.groupBy { it.model }
                .maxByOrNull { (_, rs) -> rs.sumOf { it.inputTokens + it.outputTokens } }
                ?: return@mapNotNull null
            val model = byModel.key
            val tokens = byModel.value.sumOf { it.inputTokens + it.outputTokens }

            val currentCost = ZaiCreditMultipliers.cost(model, byModel.value.sumOf { it.inputTokens }, byModel.value.sumOf { it.outputTokens })
                ?: return@mapNotNull null

            // Cheapest known alternative for the same token mix
            val cheapest = ZaiCreditMultipliers.cheapestKnown() ?: return@mapNotNull null
            if (cheapest == model) return@mapNotNull null
            val projectedCost = ZaiCreditMultipliers.cost(cheapest, byModel.value.sumOf { it.inputTokens }, byModel.value.sumOf { it.outputTokens })
                ?: return@mapNotNull null
            if (projectedCost >= currentCost) return@mapNotNull null

            RoutineConsumer(
                conversationKey = convId,
                title = null, // filled by caller with the title lookup
                agentName = records.first().source,
                model = model,
                activeDays = days,
                tokensPerDay = tokens / days,
                savingsFraction = 1.0 - projectedCost / currentCost,
                currentCreditPerDay = currentCost / days,
                projectedCreditPerDay = projectedCost / days,
            )
        }

        return results.sortedByDescending { it.currentCreditPerDay - it.projectedCreditPerDay }
    }

    // ── The actual advice ──────────────────────────────────────────────

    fun advise(
        snapshots: List<FuelSnapshotRecord>,
        usage: List<UsageRecord>,
        now: Long,
        resetAt: Long?,
    ): Advice {
        val regime = quotaRegime(snapshots, now, resetAt)
        if (regime.windowsAnalyzed < 2) return Advice.InsufficientData

        val routine = routineConsumers(usage, now)
        val projected = regime.projectedPctAtReset
        val burn = regime.burnPctPerHr ?: DEFAULT_BURN_PCT_PER_HR
        val current = snapshots.mapNotNull { it.tokensPct }.lastOrNull()

        return when {
            // Persistent pressure: history says the quota runs out regularly
            regime.exhaustions >= PRESSURE_EXHAUSTION_COUNT ->
                Advice.PersistentPressure(regime, routine)

            // This window at risk: projected to exhaust (remaining → 0) before reset
            projected != null && projected <= 0.0 ->
                Advice.AtRisk(regime, exhaustInMs(current, burn), routine)

            // Surplus regime: rarely exhausts and this window keeps healthy headroom
            regime.exhaustions <= 1 && (projected == null || projected > 10.0) ->
                Advice.Surplus(regime)

            // Normal and healthy
            else -> Advice.Healthy(regime, projected ?: 100.0)
        }
    }

    /** ms until exhaustion (remaining → 0) at the given burn rate. */
    private fun exhaustInMs(currentPct: Double?, burnPctPerHr: Double): Long? {
        val pct = currentPct ?: return null
        if (burnPctPerHr <= 0) return null
        return ((pct / burnPctPerHr) * 3_600_000).toLong()
    }

    private const val EXHAUSTION_CLUSTER_MS = 6 * 3_600_000 // one event per ~window
}
