package com.angussoftware.fueldashboard.settings

import com.angussoftware.fueldashboard.model.FuelProvider
import com.angussoftware.fueldashboard.model.FuelSettings
import com.angussoftware.fueldashboard.model.FuelSourceMode
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Loads and saves settings using the platform-agnostic settings store.
 *
 * Supports both the legacy single-provider [FuelSettings] format and the new
 * [MultiProviderSettings] format with multiple simultaneous providers.
 *
 * Migration: on load, if no multi-provider data is found, checks legacy keys
 * and auto-migrates any existing single-provider config to the new format.
 */
object FuelSettingsStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val multiProviderSerializer = MultiProviderSettings.serializer()

    // ---- Multi-provider settings ----

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

    // ---- Legacy single-provider settings (kept for backward compat) ----

    fun load(): FuelSettings {
        val modeName = loadStringSetting(FuelSettingsKeys.MODE, FuelSourceMode.CONNECTED.name)
        val mode = runCatching { FuelSourceMode.valueOf(modeName) }.getOrDefault(FuelSourceMode.CONNECTED)

        val providerName = loadStringSetting(FuelSettingsKeys.PROVIDER, FuelProvider.ZAI.name)
        val provider = runCatching { FuelProvider.valueOf(providerName) }.getOrDefault(FuelProvider.ZAI)

        val apiKey = loadStringSetting(FuelSettingsKeys.PROVIDER_KEY, "")
        val url = loadStringSetting(FuelSettingsKeys.ORCHESTRATOR_URL, "http://127.0.0.1:8321")

        return FuelSettings(
            mode = mode,
            provider = provider,
            providerApiKey = apiKey,
            orchestratorUrl = url,
        )
    }

    fun save(settings: FuelSettings) {
        saveStringSetting(FuelSettingsKeys.MODE, settings.mode.name)
        saveStringSetting(FuelSettingsKeys.PROVIDER, settings.provider.name)
        saveStringSetting(FuelSettingsKeys.PROVIDER_KEY, settings.providerApiKey)
        saveStringSetting(FuelSettingsKeys.ORCHESTRATOR_URL, settings.orchestratorUrl)
    }

    // ---- Migration ----

    /**
     * Reads legacy single-provider settings and converts them to [MultiProviderSettings].
     * If no legacy config exists, returns empty default settings.
     */
    private fun migrateFromLegacy(): MultiProviderSettings {
        val legacy = load()
        val providers = mutableListOf<ProviderConfig>()

        // Migrate z.ai direct mode config
        if (legacy.mode == FuelSourceMode.DIRECT && legacy.providerApiKey.isNotBlank()) {
            providers.add(
                ProviderConfig(
                    id = "migrated-zai",
                    kind = ProviderKind.ZAI,
                    apiKey = legacy.providerApiKey,
                ),
            )
        }

        // Migrate orchestrator/connected mode config
        val orchestratorEnabled = legacy.mode == FuelSourceMode.CONNECTED

        return MultiProviderSettings(
            providers = providers,
            orchestratorEnabled = orchestratorEnabled,
            orchestratorUrl = legacy.orchestratorUrl,
        )
    }

    /**
     * Generates a unique ID for a new provider config.
     */
    fun generateProviderId(): String = "provider-${System.currentTimeMillis()}"
}
