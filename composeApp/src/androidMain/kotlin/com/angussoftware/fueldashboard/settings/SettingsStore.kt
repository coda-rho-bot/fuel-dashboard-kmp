package com.angussoftware.fueldashboard.settings

import android.content.Context
import com.angussoftware.fueldashboard.FuelDashboardApplication

internal actual fun loadStringSetting(key: String, default: String): String {
    val prefs = FuelDashboardApplication.context
        .getSharedPreferences("fuel-dashboard", Context.MODE_PRIVATE)
    return prefs.getString(key, default) ?: default
}

internal actual fun saveStringSetting(key: String, value: String) {
    val prefs = FuelDashboardApplication.context
        .getSharedPreferences("fuel-dashboard", Context.MODE_PRIVATE)
    prefs.edit().putString(key, value).apply()
}
