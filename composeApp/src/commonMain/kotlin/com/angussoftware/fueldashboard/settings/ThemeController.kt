package com.angussoftware.fueldashboard.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode
import com.angussoftware.theming.compose.ui.theme.initializeThemeMode

/**
 * Global theme state controller with persistence.
 * Holds the selected [ColorTheme] and [ThemeMode], persisting to platform storage.
 */
object ThemeController {

    private const val KEY_COLOR_THEME = "colorTheme"
    private const val KEY_THEME_MODE = "themeMode"

    /**
     * Selected color theme. Persists on change.
     */
    var colorTheme: ColorTheme by mutableStateOf(
        loadColorTheme()
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

    fun updateColorTheme(theme: ColorTheme) {
        colorTheme = theme
        saveStringSetting(KEY_COLOR_THEME, theme.name)
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        saveStringSetting(KEY_THEME_MODE, mode.name)
    }

    private fun loadColorTheme(): ColorTheme {
        val name = loadStringSetting(KEY_COLOR_THEME, ColorTheme.Angus.name)
        return runCatching { ColorTheme.valueOf(name) }.getOrDefault(ColorTheme.Angus)
    }

    private fun loadThemeMode(): ThemeMode {
        val name = loadStringSetting(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }
}
