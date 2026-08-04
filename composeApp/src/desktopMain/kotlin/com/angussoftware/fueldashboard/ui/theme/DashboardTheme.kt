package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.theme.AngusTheme
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode

@Composable
actual fun DashboardTheme(
    colorTheme: ColorTheme,
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    AngusTheme(
        darkTheme = darkTheme,
        dynamicColor = false,
        colorTheme = colorTheme,
        content = content,
    )
}
