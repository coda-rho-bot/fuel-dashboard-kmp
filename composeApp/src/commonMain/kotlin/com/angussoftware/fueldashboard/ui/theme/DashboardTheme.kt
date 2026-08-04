package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.runtime.Composable
import com.angussoftware.theming.compose.ui.theme.ColorTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode

/**
 * Platform-specific theme wrapper that respects the selected ColorTheme and ThemeMode.
 */
@Composable
expect fun DashboardTheme(
    colorTheme: ColorTheme,
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
)
