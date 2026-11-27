package com.th0rgal.omarchdroid

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val IbmPlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
)

val Michelangelus = FontFamily(
    Font(R.font.michelangelus_regular, FontWeight.Normal),
)

val LilexMono = FontFamily(
    Font(R.font.lilex_regular, FontWeight.Normal),
    Font(R.font.lilex_medium, FontWeight.Medium),
)

@Immutable
data class OmarchPalette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHover: Color,
    val border: Color,
    val fgStrong: Color,
    val fgMuted: Color,
    val fgSubtle: Color,
    val green: Color,
    val blue: Color,
    val amber: Color,
    val red: Color,
)

val ObsidianPalette = OmarchPalette(
    isDark = true,
    bg = Color(0xFF141414),
    surface = Color(0xFF181818),
    surfaceHover = Color(0xFF1E1E1E),
    border = Color(0xFF262626),
    fgStrong = Color(0xFFF4F4F5),
    fgMuted = Color(0xFF8E8E93),
    fgSubtle = Color(0xFF636366),
    green = Color(0xFF199E70),
    blue = Color(0xFF86AEE4),
    amber = Color(0xFFC98500),
    red = Color(0xFFE05D5D),
)

val AlabasterPalette = OmarchPalette(
    isDark = false,
    bg = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceHover = Color(0xFFECEEF2),
    border = Color(0xFFDCE0E6),
    fgStrong = Color(0xFF111318),
    fgMuted = Color(0xFF5A6070),
    fgSubtle = Color(0xFF8890A0),
    green = Color(0xFF10855B),
    blue = Color(0xFF2D6FD1),
    amber = Color(0xFFB37400),
    red = Color(0xFFCC3B3B),
)

val LocalOmarchPalette = staticCompositionLocalOf { ObsidianPalette }

@Composable
fun OmarchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) ObsidianPalette else AlabasterPalette
    CompositionLocalProvider(LocalOmarchPalette provides palette, content = content)
}
