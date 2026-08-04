package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// ============================================================================
// Angus Brand Color Palette — exact values from Angus-Software-Theming
// Source: AngusColor.kt + AngusTheme.kt + AngusType.kt (jvmMain)
// These are the REAL brand colors, not generic Material3 fallbacks.
// When the theming library publishes proper KMP artifacts (JAR + .module
// metadata), this can be replaced with a direct dependency on AngusTheme.
// ============================================================================

// --- Dark scheme (Angus brand) ---
private val AngusDarkScheme = darkColorScheme(
    primary = Color(0xFF80D5D4),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F50),
    onPrimaryContainer = Color(0xFF9CF1F0),
    secondary = Color(0xFF80D4D5),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F50),
    onSecondaryContainer = Color(0xFF9CF1F1),
    tertiary = Color(0xFFA0CAFD),
    onTertiary = Color(0xFF003258),
    tertiaryContainer = Color(0xFF194975),
    onTertiaryContainer = Color(0xFFD1E4FF),
    error = Color(0xFFFDB0D4),
    onError = Color(0xFF521D3B),
    errorContainer = Color(0xFF6D3352),
    onErrorContainer = Color(0xFFFFD8E8),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDDE4E3),
    surface = Color(0xFF0E1415),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC8C8),
    outline = Color(0xFF899392),
    outlineVariant = Color(0xFF3F4949),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFDEE3E5),
    inverseOnSurface = Color(0xFF2B3133),
    inversePrimary = Color(0xFF006A6A),
    surfaceDim = Color(0xFF0E1415),
    surfaceBright = Color(0xFF343A3B),
    surfaceContainerLowest = Color(0xFF090F10),
    surfaceContainerLow = Color(0xFF171D1E),
    surfaceContainer = Color(0xFF1B2122),
    surfaceContainerHigh = Color(0xFF252B2C),
    surfaceContainerHighest = Color(0xFF303637),
)

// --- Light scheme (Angus brand) ---
private val AngusLightScheme = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F0),
    onPrimaryContainer = Color(0xFF004F50),
    secondary = Color(0xFF006A6A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CF1F1),
    onSecondaryContainer = Color(0xFF004F50),
    tertiary = Color(0xFF36618E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1E4FF),
    onTertiaryContainer = Color(0xFF194975),
    error = Color(0xFF884A6A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD8E8),
    onErrorContainer = Color(0xFF6D3352),
    background = Color(0xFFF4FBFA),
    onBackground = Color(0xFF161D1D),
    surface = Color(0xFFF5FAFB),
    onSurface = Color(0xFF171D1E),
    surfaceVariant = Color(0xFFDAE4E4),
    onSurfaceVariant = Color(0xFF3F4949),
    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC8C8),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2B3133),
    inverseOnSurface = Color(0xFFECF2F3),
    inversePrimary = Color(0xFF80D5D4),
    surfaceDim = Color(0xFFD5DBDC),
    surfaceBright = Color(0xFFF5FAFB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F6),
    surfaceContainer = Color(0xFFE9EFF0),
    surfaceContainerHigh = Color(0xFFE3E9EA),
    surfaceContainerHighest = Color(0xFFDEE3E5),
)

// --- Typography (matches AngusType.jvm.kt: SansSerif body, Serif display) ---
private val AngusTypography = androidx.compose.material3.Typography(
    displayLarge = androidx.compose.material3.Typography().displayLarge.copy(fontFamily = FontFamily.Serif),
    displayMedium = androidx.compose.material3.Typography().displayMedium.copy(fontFamily = FontFamily.Serif),
    displaySmall = androidx.compose.material3.Typography().displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineLarge = androidx.compose.material3.Typography().headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = androidx.compose.material3.Typography().headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = androidx.compose.material3.Typography().headlineSmall.copy(fontFamily = FontFamily.Serif),
    titleLarge = androidx.compose.material3.Typography().titleLarge.copy(fontFamily = FontFamily.Serif),
    titleMedium = androidx.compose.material3.Typography().titleMedium.copy(fontFamily = FontFamily.Serif),
    titleSmall = androidx.compose.material3.Typography().titleSmall.copy(fontFamily = FontFamily.Serif),
    bodyLarge = androidx.compose.material3.Typography().bodyLarge.copy(fontFamily = FontFamily.SansSerif),
    bodyMedium = androidx.compose.material3.Typography().bodyMedium.copy(fontFamily = FontFamily.SansSerif),
    bodySmall = androidx.compose.material3.Typography().bodySmall.copy(fontFamily = FontFamily.SansSerif),
    labelLarge = androidx.compose.material3.Typography().labelLarge.copy(fontFamily = FontFamily.SansSerif),
    labelMedium = androidx.compose.material3.Typography().labelMedium.copy(fontFamily = FontFamily.SansSerif),
    labelSmall = androidx.compose.material3.Typography().labelSmall.copy(fontFamily = FontFamily.SansSerif),
)

@Composable
actual fun DashboardTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) AngusDarkScheme else AngusLightScheme,
        typography = AngusTypography,
        content = content,
    )
}
