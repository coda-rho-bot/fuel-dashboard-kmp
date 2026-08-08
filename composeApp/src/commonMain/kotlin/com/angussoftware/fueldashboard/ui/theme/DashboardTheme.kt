package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.runtime.Composable

/**
 * Platform-specific theme wrapper that reads ThemeController directly.
 * This avoids passing theme params as function arguments, which would
 * cause the entire content subtree to recompose/dispose when the theme changes.
 * By reading ThemeController state inside the composable, only the theme
 * wrapper recomposes — the content subtree stays stable.
 */
@Composable
expect fun DashboardTheme(
    content: @Composable () -> Unit,
)
