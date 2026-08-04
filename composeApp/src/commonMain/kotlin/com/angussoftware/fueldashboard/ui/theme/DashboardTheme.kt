package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.runtime.Composable

/**
 * Platform-specific theme wrapper.
 * - Android: Uses Angus-Software-Theming (AngusTheme with brand colors)
 * - Desktop: Uses standard Material3 dark color scheme
 */
@Composable
expect fun DashboardTheme(content: @Composable () -> Unit)
