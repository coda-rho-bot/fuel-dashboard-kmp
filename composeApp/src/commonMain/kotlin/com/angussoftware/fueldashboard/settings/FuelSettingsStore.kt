package com.angussoftware.fueldashboard.settings

import com.angussoftware.fueldashboard.model.FuelProvider
import com.angussoftware.fueldashboard.model.FuelSettings
import com.angussoftware.fueldashboard.model.FuelSourceMode

/**
 * Loads and saves [FuelSettings] using the platform-agnostic settings store.
 */
object FuelSettingsStore {

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
}
