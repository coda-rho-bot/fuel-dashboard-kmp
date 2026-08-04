package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Angus brand colors for desktop (fallback when Angus-Software-Theming is not available)
private val AngusDarkScheme = darkColorScheme(
    primary = Color(0xFF82B1FF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF3B4759),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFF6E5676),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val AngusLightScheme = lightColorScheme(
    primary = Color(0xFF00497D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D34),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6E5676),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

@Composable
actual fun DashboardTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) AngusDarkScheme else AngusLightScheme,
        content = content,
    )
}
