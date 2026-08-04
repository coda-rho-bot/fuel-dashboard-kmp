package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.angussoftware.theming.compose.ui.theme.AngusTheme
import com.angussoftware.theming.compose.ui.theme.ColorTheme

@Composable
actual fun DashboardTheme(content: @Composable () -> Unit) {
    AngusTheme(
        darkTheme = isSystemInDarkTheme(),
        dynamicColor = false,
        colorTheme = ColorTheme.Angus,
        content = content,
    )
}
