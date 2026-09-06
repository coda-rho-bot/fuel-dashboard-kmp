package com.angussoftware.fueldashboard.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Performance indexes for the hot query paths — applied via the same
 * IF NOT EXISTS ensure-block pattern as the schema shims (no migration
 * framework; both platforms run this at driver creation).
 *
 * Architecture review H2/H3: every refresh full-scans these tables
 * (per-provider burn rates = one DISTINCT scan + one range scan per
 * provider); with no retention on Android the cost grows linearly
 * forever. Indexes turn the range scans into lookups.
 */
fun ensurePerformanceIndexes(driver: SqlDriver) {
    val indexes = listOf(
        // Per-provider burn-rate range scan: WHERE provider_id = ? AND timestamp >= ?
        "CREATE INDEX IF NOT EXISTS idx_pfs_provider_time ON provider_fuel_snapshots(provider_id, timestamp)",
        // Retention sweep + waste tiling: ORDER BY timestamp / WHERE timestamp ranges
        "CREATE INDEX IF NOT EXISTS idx_pfs_time ON provider_fuel_snapshots(timestamp)",
        // Legacy fuel snapshot series: WHERE timestamp >= ? ORDER BY timestamp
        "CREATE INDEX IF NOT EXISTS idx_fs_time ON fuel_snapshots(timestamp)",
        // Metered usage aggregates (8 per refresh): WHERE timestamp >= ? GROUP BY …
        "CREATE INDEX IF NOT EXISTS idx_usage_time ON usage_records(timestamp)",
    )
    for (sql in indexes) {
        try {
            driver.execute(null, sql, 0)
        } catch (_: Exception) {
            // Index already exists or table absent (fresh DB pre-schema) —
            // table-absent resolves on next launch after schema creation.
        }
    }
}
