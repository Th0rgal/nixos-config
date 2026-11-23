package com.th0rgal.sidesystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object StarshipColors {
    val Background = Color(0xFF0D1117)
    val SurfaceCard = Color(0xFF161B22)
    val SurfaceElevated = Color(0xFF1E242E)
    val Border = Color(0xFF2A3240)
    val BorderSubtle = Color(0xFF212834)

    val Accent = Color(0xFF7CC6FE)
    val AccentDim = Color(0xFF1A2D3F)

    val Running = Color(0xFF4ADE80)
    val RunningDim = Color(0xFF132A1E)

    val Degraded = Color(0xFFFBBF24)
    val DegradedDim = Color(0xFF2D2312)

    val Stopped = Color(0xFFF87171)
    val StoppedDim = Color(0xFF2F171A)

    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF8B949E)
    val TextMuted = Color(0xFF656F7A)
}

private val StarshipDarkColorScheme = darkColorScheme(
    primary = StarshipColors.Accent,
    onPrimary = Color(0xFF061523),
    primaryContainer = StarshipColors.AccentDim,
    onPrimaryContainer = StarshipColors.Accent,
    secondary = StarshipColors.Running,
    onSecondary = Color(0xFF072112),
    secondaryContainer = StarshipColors.RunningDim,
    onSecondaryContainer = StarshipColors.Running,
    tertiary = StarshipColors.Degraded,
    onTertiary = Color(0xFF231703),
    tertiaryContainer = StarshipColors.DegradedDim,
    onTertiaryContainer = StarshipColors.Degraded,
    error = StarshipColors.Stopped,
    onError = Color(0xFF2A090C),
    errorContainer = StarshipColors.StoppedDim,
    onErrorContainer = StarshipColors.Stopped,
    background = StarshipColors.Background,
    onBackground = StarshipColors.TextPrimary,
    surface = StarshipColors.SurfaceCard,
    onSurface = StarshipColors.TextPrimary,
    surfaceVariant = StarshipColors.SurfaceElevated,
    onSurfaceVariant = StarshipColors.TextSecondary,
    outline = StarshipColors.Border,
    outlineVariant = StarshipColors.BorderSubtle
)

private val StarshipTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

@Composable
fun SideSystemTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StarshipDarkColorScheme,
        typography = StarshipTypography,
        content = content
    )
}
