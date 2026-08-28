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
    const val FUEL_GRID_VIEW = "fuelGridView"
    // Legacy keys — kept for one-time migration only
    const val MODE = "fuelSourceMode"           // DIRECT | CONNECTED (legacy)
    const val PROVIDER = "fuelProvider"          // zai (legacy)
    const val PROVIDER_KEY = "fuelProviderKey"   // z.ai API key (legacy)
    const val ORCHESTRATOR_URL = "orchestratorUrl"
    // Active settings
    const val MULTI_PROVIDER = "multiProviderSettings" // JSON: MultiProviderSettings
    const val AGENT_SETTINGS = "agentSettings"         // JSON: AgentSettings
    const val SHOW_HELP = "showHelp"
    const val SHOW_ADVISOR = "showAdvisor"
    const val JUNIE_BALANCE = "junie_balance"
    const val JUNIE_LICENSE = "junie_license"
    const val JUNIE_LAST_CHECKED = "junie_last_checked"
    const val SERVER_API_KEY = "serverApiKey"
    const val USAGE_SOURCES = "usageSourcesSettings"     // JSON: UsageSourcesSettings
    const val EVENT_DROP_THRESHOLD = "fuelEventDropThresholdPct" // double, pct
    const val FEEDBACK_URL = "feedbackForgejoUrl"
    const val FEEDBACK_REPO = "feedbackForgejoRepo"
    const val FEEDBACK_TOKEN = "feedbackForgejoToken"
    /** Baked-in default — dedicated feedback-bot user, write:issue scope,
     *  collaborator on this repo only. Works OOB without sync. */
    const val DEFAULT_FEEDBACK_TOKEN = "04373730a873fc2989ce48014d32082c0d20543e"
    // UI state — section collapse persistence (settings page)
    const val COLLAPSED_PROVIDERS = "settingsCollapsedProviders"
    const val COLLAPSED_USAGE = "settingsCollapsedUsage"
    const val COLLAPSED_ADVANCED = "settingsCollapsedAdvanced"
    // Persistent status surfaces
    const val STATUS_NOTIFICATION_ENABLED = "statusNotificationEnabled" // Android foreground notification
    const val STATUS_NOTIFICATION_SHOW_ICON = "statusNotificationShowIcon" // Android: show icon in status bar
    const val HUD_ENABLED = "statusHudEnabled"                           // desktop HUD mini-window
    const val HUD_ALWAYS_ON_TOP = "statusHudAlwaysOnTop"                 // desktop HUD pinning
    const val HUD_X = "statusHudX"                                       // last HUD position
    const val HUD_Y = "statusHudY"
    const val HUD_WIDTH = "statusHudWidth"                               // last HUD size
    const val HUD_HEIGHT = "statusHudHeight"
    // Section order persistence (Usage/Intel tabs) — JSON array of section keys
    const val SECTION_ORDER_USAGE = "sectionOrderUsage"
    const val SECTION_ORDER_INTEL = "sectionOrderIntel"
    // Theme icon visibility in top app bar (when on, theme settings accessed via
    // app bar icon; when off, theme settings appear in the settings panel)
    const val SHOW_THEME_ICON = "showThemeIcon"
    /** Optional external/tunnel URL for QR sync. If set and reachable, used
     *  instead of the LAN URL so mobile can pair over the internet. */
    const val TUNNEL_URL = "tunnelUrl"
}
