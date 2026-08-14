package com.angussoftware.fueldashboard.database

import app.cash.sqldelight.db.SqlDriver
import com.angussoftware.fueldashboard.util.epochMillis

/**
 * State store for usage ingestion connectors.
 *
 * Two responsibilities:
 * 1. Idempotent run dedup — connectors record every external run ID they have
 *    ingested (keyed as "{connector_id}:{external_id}") so overlapping poll
 *    windows never double-count token usage.
 * 2. Agent→model attribution history — the mapping from agent to its
 *    configured model over time, so historical runs are attributed to the
 *    model that actually served them even after model switches.
 *
 * Both tables are connector-agnostic: any source connector can use them
 * without the core schema knowing about the source platform.
 */
class UsageIngestionRepository(
    driver: SqlDriver,
    private val now: () -> Long = ::epochMillis,
) {
    private val db = FuelDatabase(driver)
    private val queries = db.fuelDatabaseQueries

    /**
     * Claims a run for ingestion. Returns true if newly claimed (the caller
     * should ingest it), false if it was already ingested by a previous poll
     * (skip). Dedupes overlapping poll windows.
     */
    fun claimRun(connectorId: String, externalRunId: String): Boolean {
        val runKey = "$connectorId:$externalRunId"
        if (queries.isRunIngested(runKey).executeAsList().firstOrNull() == true) {
            return false
        }
        queries.insertIgnoredRun(runKey, connectorId, now())
        return true
    }

    fun cleanup(olderThanMs: Long = 90L * 24 * 3_600_000) {
        queries.deleteOldIngestedRuns(now() - olderThanMs)
    }

    /**
     * Records the current agent→model mapping, closing any previous record.
     * Call whenever the platform's agent configs are observed. No-op when
     * the mapping is unchanged (same agent, same model, still open).
     */
    fun recordAgentModel(agentId: String, agentName: String, model: String) {
        val open = queries.selectOpenAgentModels().executeAsList()
            .firstOrNull { it.agent_id == agentId }
        when {
            open == null -> queries.insertAgentModelRecord(agentId, agentName, model, now())
            open.model != model -> {
                queries.closeOpenAgentModelRecords(now(), agentId)
                queries.insertAgentModelRecord(agentId, agentName, model, now())
            }
            // Same model still open — nothing to do (name updates are cosmetic; skip)
        }
    }

    /** Model that served the agent at the given epoch-millis timestamp. */
    fun modelAtTime(agentId: String, timestamp: Long): String? =
        queries.selectModelAtTime(agentId, timestamp, timestamp).executeAsList().firstOrNull()

    /** Current open model mappings (agent_id → model). */
    fun openAgentModels(): Map<String, String> =
        queries.selectOpenAgentModels().executeAsList().associate { it.agent_id to it.model }
}
