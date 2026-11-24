package com.th0rgal.orbvoice

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val OrbSansFont = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold)
)

val OrbMonoFont = FontFamily(
    Font(R.font.lilex_regular, FontWeight.Normal),
    Font(R.font.lilex_medium, FontWeight.Medium)
)

val OrbSerifFont = FontFamily(
    Font(R.font.michelangelus_regular, FontWeight.Normal)
)

@Immutable
data class OrbPalette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHover: Color,
    val border: Color,
    val fgStrong: Color,
    val fgMuted: Color,
    val fgSubtle: Color,
    val green: Color,
    val amber: Color,
    val blue: Color,
    val red: Color,
    val speakerColors: List<Color>
)

val OrbDarkPalette = OrbPalette(
    isDark = true,
    bg = Color(0xFF141414),
    surface = Color(0xFF181818),
    surfaceHover = Color(0xFF1E1E1E),
    border = Color(0xFF262626),
    fgStrong = Color(0xFFF4F4F5),
    fgMuted = Color(0xFF8E8E93),
    fgSubtle = Color(0xFF636366),
    green = Color(0xFF199E70),
    amber = Color(0xFFC98500),
    blue = Color(0xFF86AEE4),
    red = Color(0xFFE05D5D),
    speakerColors = listOf(
        Color(0xFF86AEE4), // Speaker 1 — Orb Blue
        Color(0xFF199E70), // Speaker 2 — Orb Green
        Color(0xFFC98500), // Speaker 3 — Orb Amber
        Color(0xFFC084FC), // Speaker 4 — Violet
        Color(0xFFF472B6), // Speaker 5 — Rose
        Color(0xFF38BDF8)  // Speaker 6 — Cyan
    )
)

val OrbLightPalette = OrbPalette(
    isDark = false,
    bg = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceHover = Color(0xFFECEEF2),
    border = Color(0xFFDCE0E6),
    fgStrong = Color(0xFF111318),
    fgMuted = Color(0xFF5A6070),
    fgSubtle = Color(0xFF8890A0),
    green = Color(0xFF10855B),
    amber = Color(0xFFB37400),
    blue = Color(0xFF2D6FD1),
    red = Color(0xFFCC3B3B),
    speakerColors = listOf(
        Color(0xFF2D6FD1),
        Color(0xFF10855B),
        Color(0xFFB37400),
        Color(0xFF7E22CE),
        Color(0xFFBE185D),
        Color(0xFF0369A1)
    )
)

val LocalOrbPalette = staticCompositionLocalOf { OrbDarkPalette }

val OrbBg: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.bg
val OrbSurface: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.surface
val OrbSurfaceHover: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.surfaceHover
val OrbBorder: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.border
val OrbFgStrong: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.fgStrong
val OrbFgMuted: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.fgMuted
val OrbFgSubtle: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.fgSubtle
val OrbGreen: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.green
val OrbAmber: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.amber
val OrbBlue: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.blue
val OrbRed: Color @Composable @ReadOnlyComposable get() = LocalOrbPalette.current.red

private val OrbTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = OrbMonoFont,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun OrbVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) OrbDarkPalette else OrbLightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.fgStrong,
            onPrimary = palette.bg,
            background = palette.bg,
            surface = palette.surface,
            onSurface = palette.fgStrong,
            outline = palette.border
        )
    } else {
        lightColorScheme(
            primary = palette.fgStrong,
            onPrimary = Color.White,
            background = palette.bg,
            surface = palette.surface,
            onSurface = palette.fgStrong,
            outline = palette.border
        )
    }
    CompositionLocalProvider(LocalOrbPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = OrbTypography,
            content = content
        )
    }
}
