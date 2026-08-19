package com.angussoftware.fueldashboard.settings

import com.angussoftware.theming.compose.ui.settings.ThemeSettings
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode

/**
 * App adapter over the LIBRARY's shared ThemeSettings (theming-compose
 * ui.settings). State, persistence hooks, and resolution logic now live in
 * the library; this object keeps the app's singleton access pattern and
 * wires the app's SettingsStore persistence.
 */
object ThemeController {

    val settings: ThemeSettings by lazy {
        ThemeSettings.load(
            load = { key -> loadStringSetting(key, "").ifBlank { null } },
            save = { key, value -> saveStringSetting(key, value) },
        )
    }

    // Compatibility accessors (existing call sites unchanged)
    val themeMode: ThemeMode get() = settings.themeMode
    val lightColorTheme: ColorTheme get() = settings.lightColorTheme
    val darkColorTheme: ColorTheme get() = settings.darkColorTheme

    fun colorThemeFor(darkTheme: Boolean): ColorTheme = settings.colorThemeFor(darkTheme)

    fun updateLightColorTheme(theme: ColorTheme) = settings.updateLightColorTheme(theme)
    fun updateDarkColorTheme(theme: ColorTheme) = settings.updateDarkColorTheme(theme)
    fun updateThemeMode(mode: ThemeMode) = settings.updateThemeMode(mode)
}
