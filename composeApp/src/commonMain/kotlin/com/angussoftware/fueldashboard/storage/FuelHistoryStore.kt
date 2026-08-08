package com.angussoftware.fueldashboard.storage

import com.angussoftware.fueldashboard.model.FuelSnapshot
import com.angussoftware.fueldashboard.settings.saveStringSetting
import com.angussoftware.fueldashboard.settings.loadStringSetting
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local fuel history storage for burn-rate computation.
 *
 * Reuses the existing [loadStringSetting]/[saveStringSetting] expect/actual pattern.
 * Desktop: stored in java.util.prefs.Preferences.
 * Android: stored in SharedPreferences.
 *
 * Keeps the last [MAX_SNAPSHOTS] entries (72 snapshots = 6 hours at 5-min intervals).
 */
object FuelHistoryStore {

    private const val KEY = "fuelHistory"
    private const val MAX_SNAPSHOTS = 72

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(FuelSnapshot.serializer())

    fun load(): List<FuelSnapshot> {
        val raw = loadStringSetting(KEY, "[]")
        return runCatching {
            json.decodeFromString(serializer, raw)
        }.getOrElse { emptyList() }
    }

    fun add(snapshot: FuelSnapshot) {
        val current = load().toMutableList()
        current.add(snapshot)
        while (current.size > MAX_SNAPSHOTS) {
            current.removeAt(0)
        }
        save(current)
    }

    fun save(snapshots: List<FuelSnapshot>) {
        val raw = json.encodeToString(serializer, snapshots)
        saveStringSetting(KEY, raw)
    }

    fun clear() {
        saveStringSetting(KEY, "[]")
    }
}
