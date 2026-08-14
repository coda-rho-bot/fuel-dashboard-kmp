package com.angussoftware.fueldashboard.settings

import com.angussoftware.fueldashboard.usage.LettaSourceConfig
import com.angussoftware.fueldashboard.usage.UsageSourcesSettings
import kotlinx.serialization.json.Json

/**
 * Loads and saves usage-source connector configuration.
 *
 * Stored as one JSON blob under a single settings key — adding future source
 * types (openrouter, anthropic_admin, …) extends the schema without new keys.
 */
object UsageSourcesStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = UsageSourcesSettings.serializer()

    fun load(): UsageSourcesSettings {
        val raw = loadStringSetting(FuelSettingsKeys.USAGE_SOURCES, "")
        if (raw.isBlank()) return UsageSourcesSettings()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse { UsageSourcesSettings() }
    }

    fun save(settings: UsageSourcesSettings) {
        saveStringSetting(FuelSettingsKeys.USAGE_SOURCES, json.encodeToString(serializer, settings))
    }

    fun saveLetta(config: LettaSourceConfig) {
        save(load().copy(letta = config))
    }
}
