package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.theme.AngusTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun DashboardTheme(
    content: @Composable () -> Unit,
) {
    val darkTheme = when (ThemeController.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> {
            // Track the system theme so switches propagate while running
            // (Compose's isSystemInDarkTheme() always returns false on JVM).
            // Primary path is event-driven: `gsettings monitor` emits a line the
            // instant the setting changes (no polling delay). Fallback for
            // non-GNOME environments without gsettings: poll every 30s.
            var systemDark by remember { mutableStateOf(isSystemDarkMode()) }
            LaunchedEffect(Unit) {
                val monitor = try {
                    ProcessBuilder("gsettings", "monitor", "org.gnome.desktop.interface", "color-scheme")
                        .redirectErrorStream(true)
                        .start()
                } catch (_: Exception) {
                    null
                }
                if (monitor == null) {
                    // No gsettings — poll fallback
                    while (true) {
                        delay(30_000)
                        systemDark = isSystemDarkMode()
                    }
                } else {
                    // Kill the monitor process the moment this effect cancels
                    // (unblocks the read and prevents a leaked process).
                    coroutineContext[Job]?.invokeOnCompletion { monitor.destroyForcibly() }
                    withContext(Dispatchers.IO) {
                        monitor.inputStream.bufferedReader().forEachLine { line ->
                            systemDark = line.contains("dark", ignoreCase = true) &&
                                !line.contains("prefer-light", ignoreCase = true)
                        }
                    }
                }
            }
            systemDark
        }
    }
    // Resolve the palette FROM the resolved dark/light state — community
    // palettes (Gruvbox, Catppuccin, …) carry their own light/dark identity
    // and ignore the darkTheme flag, so SYSTEM mode must pick the palette,
    // not just the flag.
    val colorTheme = ThemeController.colorThemeFor(darkTheme)
    println("[DashboardTheme] mode=${ThemeController.themeMode} dark=$darkTheme palette=$colorTheme")
    AngusTheme(
        darkTheme = darkTheme,
        dynamicColor = false,
        colorTheme = colorTheme,
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
