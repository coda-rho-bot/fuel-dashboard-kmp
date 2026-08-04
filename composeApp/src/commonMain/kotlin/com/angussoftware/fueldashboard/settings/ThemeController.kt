package com.angussoftware.fueldashboard.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode
import com.angussoftware.theming.compose.ui.theme.initializeThemeMode

/**
 * Global theme state controller with persistence.
 *
 * Stores separate [lightColorTheme] and [darkColorTheme] selections so the user can
 * pick different color schemes for light and dark modes (matching the AngusSoftwareApp
 * pattern). The [activeColorTheme] property resolves which one to use based on the
 * current [themeMode].
 */
object ThemeController {

    private const val KEY_LIGHT_COLOR_THEME = "lightColorTheme"
    private const val KEY_DARK_COLOR_THEME = "darkColorTheme"
    private const val KEY_THEME_MODE = "themeMode"

    /**
     * Color theme to use when the app is in light mode. Persists on change.
     */
    var lightColorTheme: ColorTheme by mutableStateOf(
        loadColorTheme(KEY_LIGHT_COLOR_THEME, ColorTheme.Angus)
    )
        private set

    /**
     * Color theme to use when the app is in dark mode. Persists on change.
     */
    var darkColorTheme: ColorTheme by mutableStateOf(
        loadColorTheme(KEY_DARK_COLOR_THEME, ColorTheme.AngusDark)
    )
        private set

    /**
     * Selected theme mode (Light/Dark/System). Persists on change.
     */
    var themeMode: ThemeMode by mutableStateOf(
        loadThemeMode()
    )
        private set

    init {
        // Sync the theming library's internal ThemeMode state
        initializeThemeMode(themeMode)
    }

    /**
     * The color theme that should actually be applied, resolved from [lightColorTheme]
     * or [darkColorTheme] based on the current [themeMode].
     *
     * - LIGHT → [lightColorTheme]
     * - DARK → [darkColorTheme]
     * - SYSTEM → [darkColorTheme] (the actual light/dark resolution happens at render time)
     */
    val activeColorTheme: ColorTheme
        get() = when (themeMode) {
            ThemeMode.LIGHT -> lightColorTheme
            ThemeMode.DARK -> darkColorTheme
            ThemeMode.SYSTEM -> darkColorTheme
        }

    fun updateLightColorTheme(theme: ColorTheme) {
        lightColorTheme = theme
        saveStringSetting(KEY_LIGHT_COLOR_THEME, theme.name)
    }

    fun updateDarkColorTheme(theme: ColorTheme) {
        darkColorTheme = theme
        saveStringSetting(KEY_DARK_COLOR_THEME, theme.name)
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        saveStringSetting(KEY_THEME_MODE, mode.name)
    }

    private fun loadColorTheme(key: String, default: ColorTheme): ColorTheme {
        val name = loadStringSetting(key, default.name)
        return runCatching { ColorTheme.valueOf(name) }.getOrDefault(default)
    }

    private fun loadThemeMode(): ThemeMode {
        val name = loadStringSetting(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }
}
