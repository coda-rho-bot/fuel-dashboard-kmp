package com.angussoftware.fueldashboard.settings

import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import kotlinx.serialization.json.Json

/**
 * Loads and saves multi-provider settings using the platform-agnostic settings store.
 *
 * Legacy single-provider settings (DIRECT/CONNECTED mode) are auto-migrated on first load.
 */
object FuelSettingsStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val multiProviderSerializer = MultiProviderSettings.serializer()

    fun loadMultiProvider(): MultiProviderSettings {
        val raw = loadStringSetting(FuelSettingsKeys.MULTI_PROVIDER, "")
        if (raw.isNotBlank()) {
            return runCatching {
                json.decodeFromString(multiProviderSerializer, raw)
            }.getOrElse { migrateFromLegacy() }
        }
        return migrateFromLegacy()
    }

    fun saveMultiProvider(settings: MultiProviderSettings) {
        val raw = json.encodeToString(multiProviderSerializer, settings)
        saveStringSetting(FuelSettingsKeys.MULTI_PROVIDER, raw)
    }

    /**
     * Reads legacy single-provider keys and converts them to [MultiProviderSettings].
     * If no legacy config exists, returns empty default settings.
     */
    private fun migrateFromLegacy(): MultiProviderSettings {
        val providers = mutableListOf<ProviderConfig>()

        // Migrate z.ai direct-mode API key
        val legacyApiKey = loadStringSetting(FuelSettingsKeys.PROVIDER_KEY, "")
        if (legacyApiKey.isNotBlank()) {
            providers.add(
                ProviderConfig(
                    id = "migrated-zai",
                    kind = ProviderKind.ZAI,
                    apiKey = legacyApiKey,
                ),
            )
        }

        // Migrate orchestrator/connected-mode URL
        val legacyMode = loadStringSetting(FuelSettingsKeys.MODE, "")
        val legacyUrl = loadStringSetting(FuelSettingsKeys.ORCHESTRATOR_URL, "")
        if (legacyMode == "CONNECTED" && legacyUrl.isNotBlank()) {
            providers.add(
                ProviderConfig(
                    id = "migrated-orchestrator",
                    kind = ProviderKind.CONNECTED_API,
                    serverUrl = legacyUrl,
                ),
            )
        }

        return MultiProviderSettings(providers = providers)
    }

    /**
     * Generates a unique ID for a new provider config.
     */
    fun generateProviderId(): String = "provider-${System.currentTimeMillis()}"
}
