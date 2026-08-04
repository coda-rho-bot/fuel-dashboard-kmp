package com.angussoftware.fueldashboard.settings

/**
 * Platform-agnostic key-value settings persistence.
 * Desktop: java.util.prefs.Preferences
 * Android: SharedPreferences
 */
internal expect fun loadStringSetting(key: String, default: String): String

internal expect fun saveStringSetting(key: String, value: String)
