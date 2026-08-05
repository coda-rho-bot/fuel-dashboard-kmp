package com.angussoftware.fueldashboard.settings

/**
 * Platform-agnostic key-value settings persistence.
 * Desktop: java.util.prefs.Preferences
 * Android: SharedPreferences
 */
internal expect fun loadStringSetting(key: String, default: String): String

internal expect fun saveStringSetting(key: String, value: String)

// ---------------------------------------------------------------------------
// Fuel source settings keys
// ---------------------------------------------------------------------------

internal object FuelSettingsKeys {
    const val MODE = "fuelSourceMode"           // DIRECT | CONNECTED (legacy)
    const val PROVIDER = "fuelProvider"          // zai (legacy)
    const val PROVIDER_KEY = "fuelProviderKey"   // z.ai API key (legacy)
    const val ORCHESTRATOR_URL = "orchestratorUrl"
    const val MULTI_PROVIDER = "multiProviderSettings" // JSON: MultiProviderSettings
}
