package com.angussoftware.fueldashboard.settings

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * User-defined section ordering for tabbed pages (Usage, Intel).
 *
 * Order persists as a JSON array of section keys and syncs across devices
 * via [com.angussoftware.fueldashboard.model.SettingsSyncData]. Keys absent
 * from the saved order (e.g. new sections after an update) append at the end
 * in their default order; stale keys in the saved order are ignored.
 */
object SectionOrder {

    /** Canonical section keys for the Usage tab, in default order. */
    val USAGE_KEYS = listOf("metered", "drain", "waste")

    /** Canonical section keys for the Intel tab, in default order. */
    val INTEL_KEYS = listOf("events")

    private val json = Json { ignoreUnknownKeys = true }

    /** Current persisted order for a key set — saved order first, unknown keys appended. */
    fun load(settingKey: String, defaultKeys: List<String>): List<String> {
        val saved = runCatching {
            json.decodeFromString(
                ListSerializer(String.serializer()),
                loadStringSetting(settingKey, "[]"),
            )
        }.getOrNull().orEmpty()
        val savedKnown = saved.filter { it in defaultKeys }
        val appended = defaultKeys.filter { it !in savedKnown }
        return savedKnown + appended
    }

    fun save(settingKey: String, order: List<String>) {
        saveStringSetting(settingKey, json.encodeToString(ListSerializer(String.serializer()), order))
    }

    /** Move a key up (-1) / down (+1); persists and returns the new order. */
    fun move(settingKey: String, defaultKeys: List<String>, key: String, offset: Int): List<String> {
        val order = load(settingKey, defaultKeys).toMutableList()
        val index = order.indexOf(key)
        if (index < 0) return order
        val target = index + offset
        if (target < 0 || target >= order.size) return order
        val item = order.removeAt(index)
        order.add(target, item)
        save(settingKey, order)
        return order
    }

    fun loadUsage(): List<String> = load(FuelSettingsKeys.SECTION_ORDER_USAGE, USAGE_KEYS)
    fun loadIntel(): List<String> = load(FuelSettingsKeys.SECTION_ORDER_INTEL, INTEL_KEYS)
}
