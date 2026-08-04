package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.theme.AngusTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode
import java.io.File

@Composable
actual fun DashboardTheme(
    content: @Composable () -> Unit,
) {
    val darkTheme = when (ThemeController.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> remember { isSystemDarkMode() }
    }
    AngusTheme(
        darkTheme = darkTheme,
        dynamicColor = false,
        colorTheme = ThemeController.colorTheme,
        content = content,
    )
}

/**
 * Detects system dark mode on Linux desktop (GNOME/KDE/etc).
 * Compose's isSystemInDarkTheme() always returns false on JVM — this is a workaround.
 * Checks gsettings (GNOME), then falls back to GTK theme name inspection.
 */
private fun isSystemDarkMode(): Boolean {
    return try {
        // GNOME: check org.gnome.desktop.interface color-scheme
        val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.contains("dark", ignoreCase = true)) {
            return true
        }

        // Fallback: check GTK_THEME env var or ~/.config/gtk-3.0/settings.ini
        val gtkTheme = System.getenv("GTK_THEME") ?: ""
        if (gtkTheme.contains("dark", ignoreCase = true) || gtkTheme.contains("-dark", ignoreCase = true)) {
            return true
        }

        // Fallback: read GTK settings file
        val gtkSettings = File(System.getProperty("user.home"), ".config/gtk-3.0/settings.ini")
        if (gtkSettings.exists()) {
            val content = gtkSettings.readText()
            if (content.contains("gtk-application-prefer-dark-theme=1", ignoreCase = true)) {
                return true
            }
        }

        // KDE: check ~/.config/kdeglobals
        val kdeGlobals = File(System.getProperty("user.home"), ".config/kdeglobals")
        if (kdeGlobals.exists()) {
            val content = kdeGlobals.readText()
            // Look for dark color schemes
            if (content.contains("ColorScheme=", ignoreCase = true) &&
                content.lowercase().let {
                    it.contains("dark") || it.contains("breeze-dark") || it.contains("night")
                }) {
                return true
            }
        }

        false
    } catch (_: Exception) {
        false
    }
}
