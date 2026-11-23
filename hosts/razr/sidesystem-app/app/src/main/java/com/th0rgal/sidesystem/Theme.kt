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

val OrbBg = Color(0xFF141414)
val OrbSurface = Color(0xFF181818)
val OrbSurfaceHover = Color(0xFF1E1E1E)
val OrbBorder = Color(0xFF262626)
val OrbFgStrong = Color(0xFFF4F4F5)
val OrbFgMuted = Color(0xFF8E8E93)
val OrbFgSubtle = Color(0xFF636366)
val OrbGreen = Color(0xFF199E70)
val OrbAmber = Color(0xFFC98500)
val OrbBlue = Color(0xFF86AEE4)
val OrbRed = Color(0xFFE05D5D)

private val OrbDarkColorScheme = darkColorScheme(
    primary = OrbFgStrong,
    onPrimary = OrbBg,
    primaryContainer = OrbSurfaceHover,
    onPrimaryContainer = OrbFgStrong,
    secondary = OrbBlue,
    onSecondary = OrbBg,
    secondaryContainer = OrbSurfaceHover,
    onSecondaryContainer = OrbFgStrong,
    tertiary = OrbGreen,
    onTertiary = OrbBg,
    tertiaryContainer = OrbSurfaceHover,
    onTertiaryContainer = OrbGreen,
    error = OrbRed,
    onError = OrbBg,
    errorContainer = Color(0xFF2A1616),
    onErrorContainer = OrbRed,
    background = OrbBg,
    onBackground = OrbFgStrong,
    surface = OrbSurface,
    onSurface = OrbFgStrong,
    surfaceVariant = OrbSurfaceHover,
    onSurfaceVariant = OrbFgMuted,
    outline = OrbBorder,
    outlineVariant = OrbBorder
)

private val OrbTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.35).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.1).sp
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
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.1).sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun SideSystemTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OrbDarkColorScheme,
        typography = OrbTypography,
        content = content
    )
}
