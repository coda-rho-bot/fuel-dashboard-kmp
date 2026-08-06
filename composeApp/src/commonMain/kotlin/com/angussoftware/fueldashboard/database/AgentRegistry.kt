package com.angussoftware.fueldashboard.database

import app.cash.sqldelight.db.SqlDriver
import com.angussoftware.fueldashboard.util.epochMillis

/**
 * Persistent agent registry — survives app restarts.
 * Agents registered via MCP or HTTP are stored in SQLite.
 */
class AgentRegistry(driver: SqlDriver) {
    private val db = FuelDatabase(driver)
    private val queries = db.fuelDatabaseQueries

    fun upsert(
        id: String,
        name: String,
        model: String? = null,
        framework: String? = null,
        command: String? = null,
        status: String = "registered",
    ) {
        queries.upsertAgent(
            id = id,
            name = name,
            model = model,
            framework = framework,
            command = command,
            status = status,
            registered_at = epochMillis(),
        )
    }

    fun remove(id: String) {
        queries.deleteAgent(id)
    }

    fun removeAll() {
        queries.deleteAllAgents()
    }

    fun all(): List<RegisteredAgentRecord> {
        return queries.selectAllAgents().executeAsList().map { row ->
            RegisteredAgentRecord(
                id = row.id,
                name = row.name,
                model = row.model,
                framework = row.framework,
                command = row.command,
                status = row.status,
                registeredAt = row.registered_at,
            )
        }
    }
}

data class RegisteredAgentRecord(
    val id: String,
    val name: String,
    val model: String?,
    val framework: String?,
    val command: String?,
    val status: String,
    val registeredAt: Long,
)
