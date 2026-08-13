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
        ensureModelDrainRatesTable(driver)
        ensureProviderFuelSnapshotsTable(driver)
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

    private fun ensureModelDrainRatesTable(driver: SqlDriver) {
        try {
            driver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS model_drain_rates (
                    model TEXT NOT NULL PRIMARY KEY,
                    total_fuel_consumed REAL NOT NULL DEFAULT 0,
                    sample_count INTEGER NOT NULL DEFAULT 0,
                    active_agent_count_avg REAL NOT NULL DEFAULT 0,
                    first_seen INTEGER NOT NULL,
                    last_updated INTEGER NOT NULL,
                    avg_drain_per_hr REAL NOT NULL DEFAULT 0
                )
                """.trimIndent(),
                0,
            )
        } catch (e: Exception) {
            // Table already exists or other non-fatal error
        }
    }

    private fun ensureProviderFuelSnapshotsTable(driver: SqlDriver) {
        try {
            driver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS provider_fuel_snapshots (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    provider_id TEXT NOT NULL,
                    provider_name TEXT NOT NULL,
                    provider_type TEXT NOT NULL,
                    remaining_pct REAL,
                    reset_at INTEGER,
                    window_hours REAL
                )
                """.trimIndent(),
                0,
            )
        } catch (e: Exception) {
            // Table already exists or other non-fatal error
        }
    }
}
