package com.angussoftware.fueldashboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.theme.AngusTheme
import com.angussoftware.theming.compose.ui.theme.ColorTheme
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
    val dynamicColor = ThemeController.colorTheme == ColorTheme.Angus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    AngusTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        colorTheme = ThemeController.colorTheme,
        content = content,
    )
}
