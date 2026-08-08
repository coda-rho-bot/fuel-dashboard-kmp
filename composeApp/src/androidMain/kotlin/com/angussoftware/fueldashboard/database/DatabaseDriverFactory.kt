package com.angussoftware.fueldashboard.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.angussoftware.fueldashboard.FuelDashboardApplication

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = FuelDatabase.Schema,
            context = FuelDashboardApplication.context,
            name = "fuel-decisions.db",
        )
    }
}
