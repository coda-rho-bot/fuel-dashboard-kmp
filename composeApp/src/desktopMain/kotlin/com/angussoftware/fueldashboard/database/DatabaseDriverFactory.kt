package com.angussoftware.fueldashboard.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val dbPath = File(System.getProperty("user.home"), ".fuel-dashboard/decisions.db")
        dbPath.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.absolutePath}")
        // Use IF NOT EXISTS via schema version check
        try {
            FuelDatabase.Schema.create(driver)
        } catch (e: Exception) {
            // Table already exists — this is fine, the DB was created on a previous run
        }
        // Ensure new tables added after initial schema are created (no migration framework)
        ensureFuelSnapshotsTable(driver)
        return driver
    }

    private fun ensureFuelSnapshotsTable(driver: SqlDriver) {
        try {
            driver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS fuel_snapshots (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    tokens_pct REAL,
                    session_pct REAL,
                    active_agent_count INTEGER NOT NULL DEFAULT 0,
                    active_models TEXT,
                    reset_at INTEGER
                )
                """.trimIndent(),
                0,
            )
        } catch (e: Exception) {
            // Table already exists or other non-fatal error
        }
    }
}
