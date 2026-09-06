package com.angussoftware.fueldashboard.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.angussoftware.fueldashboard.FuelDashboardApplication

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(
            schema = FuelDatabase.Schema,
            context = FuelDashboardApplication.context,
            name = "fuel-decisions.db",
        )
        // Same ensure-block pattern as desktop — see PerformanceIndexes.kt
        ensurePerformanceIndexes(driver)
        return driver
    }
}
