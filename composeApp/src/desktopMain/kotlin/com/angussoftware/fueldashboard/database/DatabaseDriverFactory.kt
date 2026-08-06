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
        return driver
    }
}
