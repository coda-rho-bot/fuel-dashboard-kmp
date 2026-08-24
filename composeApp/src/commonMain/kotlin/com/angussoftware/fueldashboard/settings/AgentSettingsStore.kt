package com.angussoftware.fueldashboard.settings

import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import kotlinx.serialization.json.Json

/**
 * Loads and saves agent settings using the platform-agnostic settings store.
 *
 * Follows the same pattern as [FuelSettingsStore] — serialize [AgentSettings]
 * as JSON, persist via a single settings key.
 */
object AgentSettingsStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = AgentSettings.serializer()

    fun load(): AgentSettings {
        val raw = loadStringSetting(FuelSettingsKeys.AGENT_SETTINGS, "")
        if (raw.isNotBlank()) {
            return runCatching {
                json.decodeFromString(serializer, raw)
            }.getOrElse { AgentSettings() }
        }
        return AgentSettings()
    }

    fun save(settings: AgentSettings) {
        val raw = json.encodeToString(serializer, settings)
        saveStringSetting(FuelSettingsKeys.AGENT_SETTINGS, raw)
    }

    /**
     * Generates a unique ID for a new agent config.
     * Milliseconds + random suffix — two agents added in the same
     * millisecond (bulk import, fast taps) must not collide.
     */
    fun generateAgentId(): String =
        "agent-${System.currentTimeMillis()}-${kotlin.random.Random.nextInt(1_000, 9_999)}"
}
