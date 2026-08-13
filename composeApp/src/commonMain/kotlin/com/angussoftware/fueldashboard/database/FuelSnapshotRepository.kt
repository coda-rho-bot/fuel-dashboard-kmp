package com.angussoftware.fueldashboard.database

import com.angussoftware.fueldashboard.util.epochMillis
import app.cash.sqldelight.db.SqlDriver

data class ModelDrainRate(
    val model: String,
    val totalFuelConsumed: Double,
    val sampleCount: Int,
    val activeAgentCountAvg: Double,
    val firstSeen: Long,
    val lastUpdated: Long,
    val avgDrainPerHr: Double,
)

data class FuelSnapshotRecord(
    val id: Long,
    val timestamp: Long,
    val tokensPct: Double?,
    val sessionPct: Double?,
    val activeAgentCount: Int,
    val activeModels: String?,
    val resetAt: Long?,
)

/**
 * Stores periodic fuel gauge snapshots for burn-rate computation and
 * consumption attribution. Each snapshot captures the fuel state at a
 * point in time plus which agents/models were active — enabling
 * per-model drain-rate analysis.
 */
class FuelSnapshotRepository(driver: SqlDriver) {
    private val db = FuelDatabase(driver)
    private val queries = db.fuelDatabaseQueries

    fun insert(
        tokensPct: Double?,
        sessionPct: Double?,
        activeAgentCount: Int,
        activeModels: String?,
        resetAt: Long?,
    ) {
        // Before inserting, check the previous snapshot for fuel-drop attribution
        if (tokensPct != null) {
            attributeFuelDelta(tokensPct, activeModels, activeAgentCount)
        }

        queries.insertFuelSnapshot(
            timestamp = epochMillis(),
            tokens_pct = tokensPct,
            session_pct = sessionPct,
            active_agent_count = activeAgentCount.toLong(),
            active_models = activeModels,
            reset_at = resetAt,
        )
    }

    /**
     * Compares current fuel to the previous snapshot. If fuel dropped, attributes
     * the consumption to the models active during that interval.
     *
     * Attribution is proportional: if 2 models were active, each gets half the
     * delta credited. This is a heuristic — true per-model attribution would
     * require per-turn token tracking. Over time, models that consistently
     * appear during high-drain periods accumulate more cost.
     */
    private fun attributeFuelDelta(currentPct: Double, activeModels: String?, activeAgentCount: Int) {
        val prev = queries.selectRecentFuelSnapshots(1).executeAsList().firstOrNull() ?: return
        val prevPct = prev.tokens_pct ?: return

        // Only attribute actual drops (not resets or increases from window sliding)
        val delta = prevPct - currentPct
        if (delta <= 0.5) return // ignore noise (<0.5% change)

        val models = activeModels?.split(",")?.filter { it.isNotBlank() } ?: return
        if (models.isEmpty()) return

        val perModelDelta = delta / models.size
        val now = epochMillis()

        for (model in models) {
            // Try increment existing record
            val existing = queries.selectAllModelDrainRates().executeAsList()
                .find { it.model == model }

            if (existing != null) {
                queries.incrementModelDrainRate(
                    delta = perModelDelta,
                    agent_count = activeAgentCount.toDouble(),
                    timestamp = now,
                    model = model,
                )
            } else {
                // First time seeing this model
                val hoursActive = 0.1 // minimum to avoid div-by-zero
                queries.upsertModelDrainRate(
                    model = model,
                    total_fuel_consumed = perModelDelta,
                    sample_count = 1,
                    active_agent_count_avg = activeAgentCount.toDouble(),
                    first_seen = now,
                    last_updated = now,
                    avg_drain_per_hr = perModelDelta / hoursActive,
                )
            }
        }
    }

    /**
     * Returns all model drain rates sorted by total consumption.
     */
    fun getModelDrainRates(): List<ModelDrainRate> {
        return queries.selectAllModelDrainRates().executeAsList().map { row ->
            ModelDrainRate(
                model = row.model,
                totalFuelConsumed = row.total_fuel_consumed,
                sampleCount = row.sample_count.toInt(),
                activeAgentCountAvg = row.active_agent_count_avg,
                firstSeen = row.first_seen,
                lastUpdated = row.last_updated,
                avgDrainPerHr = row.avg_drain_per_hr,
            )
        }
    }

    fun getRecent(limit: Int = 100): List<FuelSnapshotRecord> {
        return queries.selectRecentFuelSnapshots(limit.toLong()).executeAsList().map { row ->
            FuelSnapshotRecord(
                id = row.id,
                timestamp = row.timestamp,
                tokensPct = row.tokens_pct,
                sessionPct = row.session_pct,
                activeAgentCount = row.active_agent_count.toInt(),
                activeModels = row.active_models,
                resetAt = row.reset_at,
            )
        }
    }

    fun getSince(since: Long): List<FuelSnapshotRecord> {
        return queries.selectFuelSnapshotsSince(since).executeAsList().map { row ->
            FuelSnapshotRecord(
                id = row.id,
                timestamp = row.timestamp,
                tokensPct = row.tokens_pct,
                sessionPct = row.session_pct,
                activeAgentCount = row.active_agent_count.toInt(),
                activeModels = row.active_models,
                resetAt = row.reset_at,
            )
        }
    }

    /**
     * Computes the real burn rate (% per hour) from the last hour of snapshots.
     * Uses linear regression on tokens_pct over time for accuracy.
     * Returns null if insufficient data (< 3 points or < 10 minutes span).
     */
    fun computeBurnRate(windowMs: Long = 3_600_000): Double? {
        val now = epochMillis()
        val snapshots = getSince(now - windowMs)
            .filter { it.tokensPct != null }
        if (snapshots.size < 3) return null

        val first = snapshots.first()
        val last = snapshots.last()
        val timeSpanMs = last.timestamp - first.timestamp
        if (timeSpanMs < 600_000) return null // need at least 10 min

        val fuelDelta = (first.tokensPct ?: return null) - (last.tokensPct ?: return null)
        val hoursElapsed = timeSpanMs / 3_600_000.0

        if (hoursElapsed <= 0) return null
        return fuelDelta / hoursElapsed
    }

    /**
     * Projects when fuel will be exhausted based on the current burn rate.
     * Returns (hoursUntilExhaustion, projectedRemainingPct) or null if
     * burn rate can't be computed.
     */
    fun projectExhaustion(
        currentPct: Double,
        resetAt: Long?,
        burnRate: Double,
    ): ExhaustionProjection? {
        if (burnRate <= 0) {
            // Not burning or gaining fuel (reset window slid)
            return ExhaustionProjection(
                hoursUntilExhaustion = null,
                projectedRemainingAtReset = currentPct,
                willMakeIt = true,
            )
        }

        val now = epochMillis()
        val msUntilReset = resetAt?.let { it - now } ?: 3_600_000L * 5
        val hoursUntilReset = maxOf(0.0, msUntilReset / 3_600_000.0)

        val hoursUntilExhaustion = currentPct / burnRate
        val projectedRemainingAtReset = currentPct - burnRate * hoursUntilReset

        return ExhaustionProjection(
            hoursUntilExhaustion = hoursUntilExhaustion,
            projectedRemainingAtReset = projectedRemainingAtReset,
            willMakeIt = projectedRemainingAtReset > 0,
        )
    }

    /**
     * Removes snapshots older than the cutoff to prevent unbounded growth.
     */
    fun cleanup(olderThanMs: Long = 7 * 24 * 3_600_000L) { // 7 days default
        queries.deleteOldFuelSnapshots(epochMillis() - olderThanMs)
    }
}

data class ExhaustionProjection(
    val hoursUntilExhaustion: Double?,
    val projectedRemainingAtReset: Double,
    val willMakeIt: Boolean,
)
