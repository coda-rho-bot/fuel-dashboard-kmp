package com.angussoftware.fueldashboard.settings

import java.util.prefs.Preferences

private val prefs: Preferences = Preferences.userRoot().node("fuel-dashboard")

internal actual fun loadStringSetting(key: String, default: String): String {
    return prefs.get(key, default)
}

internal actual fun saveStringSetting(key: String, value: String) {
    prefs.put(key, value)
}
