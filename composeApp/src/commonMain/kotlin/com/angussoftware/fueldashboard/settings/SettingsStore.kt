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
    // Legacy keys — kept for one-time migration only
    const val MODE = "fuelSourceMode"           // DIRECT | CONNECTED (legacy)
    const val PROVIDER = "fuelProvider"          // zai (legacy)
    const val PROVIDER_KEY = "fuelProviderKey"   // z.ai API key (legacy)
    const val ORCHESTRATOR_URL = "orchestratorUrl"
    // Active settings
    const val MULTI_PROVIDER = "multiProviderSettings" // JSON: MultiProviderSettings
    const val AGENT_SETTINGS = "agentSettings"         // JSON: AgentSettings
    const val SHOW_HELP = "showHelp"
    const val JUNIE_BALANCE = "junie_balance"
    const val JUNIE_LICENSE = "junie_license"
    const val JUNIE_LAST_CHECKED = "junie_last_checked"
    const val SERVER_API_KEY = "serverApiKey"
    const val USAGE_SOURCES = "usageSourcesSettings"     // JSON: UsageSourcesSettings
    const val EVENT_DROP_THRESHOLD = "fuelEventDropThresholdPct" // double, pct
}
