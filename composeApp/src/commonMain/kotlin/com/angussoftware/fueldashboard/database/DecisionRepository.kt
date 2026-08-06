package com.angussoftware.fueldashboard.database

import com.angussoftware.fueldashboard.util.epochMillis
import app.cash.sqldelight.db.SqlDriver

data class DecisionRecord(
    val id: Long,
    val agentId: String,
    val modelHandle: String,
    val provider: String,
    val tier: String,
    val complexity: String,
    val utilizationRatio: Double,
    val headroom: Long,
    val reason: String,
    val timestamp: Long,
)

class DecisionRepository(driver: SqlDriver) {
    private val db = FuelDatabase(driver)
    private val queries = db.fuelDatabaseQueries

    fun insert(
        agentId: String,
        modelHandle: String,
        provider: String,
        tier: String,
        complexity: String,
        utilizationRatio: Double,
        headroom: Int,
        reason: String,
    ) {
        queries.insertDecision(
            agent_id = agentId,
            model_handle = modelHandle,
            provider = provider,
            tier = tier,
            complexity = complexity,
            utilization_ratio = utilizationRatio,
            headroom = headroom.toLong(),
            reason = reason,
            timestamp = epochMillis(),
        )
    }

    fun getRecent(limit: Int = 20): List<DecisionRecord> {
        return queries.selectRecentDecisions(limit.toLong()).executeAsList().map { row ->
            DecisionRecord(
                id = row.id,
                agentId = row.agent_id,
                modelHandle = row.model_handle,
                provider = row.provider,
                tier = row.tier,
                complexity = row.complexity,
                utilizationRatio = row.utilization_ratio,
                headroom = row.headroom,
                reason = row.reason,
                timestamp = row.timestamp,
            )
        }
    }
}
