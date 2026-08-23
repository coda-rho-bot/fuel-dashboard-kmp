package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.theme.AngusTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode

@Composable
actual fun DashboardTheme(
    content: @Composable () -> Unit,
) {
    val darkTheme = when (ThemeController.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    // Resolve the palette FROM the resolved dark/light state — community
    // palettes carry their own light/dark identity and ignore the darkTheme
    // flag, so SYSTEM mode must pick the palette, not just the flag.
    val colorTheme = ThemeController.colorThemeFor(darkTheme)
    AngusTheme(
        darkTheme = darkTheme,
        colorTheme = colorTheme,
        content = content,
    )
}
