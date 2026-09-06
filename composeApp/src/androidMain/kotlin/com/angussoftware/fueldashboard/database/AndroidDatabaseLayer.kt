package com.angussoftware.fueldashboard.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver

/**
 * Process-owned persistence layer (architecture review H2).
 *
 * Previously MainActivity.wireFuelCallbacks() created a NEW
 * AndroidSqliteDriver on every Activity recreation (theme change, process
 * restore) and never closed the old one — leaking an open SQLite helper
 * per recreation, with multi-connection SQLITE_BUSY risk against the
 * background worker's writes. It also never ran any retention sweep:
 * Android snapshot tables grew unbounded (2,880 cycles/day × (1 + N)
 * rows) with per-refresh full scans (now indexed — see
 * [ensurePerformanceIndexes]).
 *
 * Owned by FuelDashboardApplication: one driver + one repository for the
 * process lifetime, with a daily retention sweep piggybacked on the
 * snapshot-logging callback (same cadence pattern as desktop main.kt).
 */
class AndroidDatabaseLayer(context: Context) {
    val driver: SqlDriver = DatabaseDriverFactory().createDriver()
    val repository = FuelSnapshotRepository(driver)

    private var lastCleanupMs = 0L

    /** Called on every snapshot log; sweeps at most once per 24h. */
    fun maybeCleanup(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastCleanupMs < 24 * 3_600_000L) return
        lastCleanupMs = nowMs
        runCatching { repository.cleanup() }
    }

    companion object {
        @Volatile
        private var instance: AndroidDatabaseLayer? = null

        fun get(context: Context): AndroidDatabaseLayer =
            instance ?: synchronized(this) {
                instance ?: AndroidDatabaseLayer(context.applicationContext).also { instance = it }
            }
    }
}
