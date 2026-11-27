package md.thomas.sidesystem

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

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

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
    val red: Color
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
    red = Color(0xFFE05D5D)
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
    red = Color(0xFFCC3B3B)
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

private val OrbDarkColorScheme = darkColorScheme(
    primary = OrbDarkPalette.fgStrong,
    onPrimary = OrbDarkPalette.bg,
    primaryContainer = OrbDarkPalette.surfaceHover,
    onPrimaryContainer = OrbDarkPalette.fgStrong,
    secondary = OrbDarkPalette.blue,
    onSecondary = OrbDarkPalette.bg,
    secondaryContainer = OrbDarkPalette.surfaceHover,
    onSecondaryContainer = OrbDarkPalette.fgStrong,
    tertiary = OrbDarkPalette.green,
    onTertiary = OrbDarkPalette.bg,
    tertiaryContainer = OrbDarkPalette.surfaceHover,
    onTertiaryContainer = OrbDarkPalette.green,
    error = OrbDarkPalette.red,
    onError = OrbDarkPalette.bg,
    errorContainer = Color(0xFF2A1616),
    onErrorContainer = OrbDarkPalette.red,
    background = OrbDarkPalette.bg,
    onBackground = OrbDarkPalette.fgStrong,
    surface = OrbDarkPalette.surface,
    onSurface = OrbDarkPalette.fgStrong,
    surfaceVariant = OrbDarkPalette.surfaceHover,
    onSurfaceVariant = OrbDarkPalette.fgMuted,
    outline = OrbDarkPalette.border,
    outlineVariant = OrbDarkPalette.border
)

private val OrbLightColorScheme = lightColorScheme(
    primary = OrbLightPalette.fgStrong,
    onPrimary = Color.White,
    primaryContainer = OrbLightPalette.surfaceHover,
    onPrimaryContainer = OrbLightPalette.fgStrong,
    secondary = OrbLightPalette.blue,
    onSecondary = Color.White,
    secondaryContainer = OrbLightPalette.surfaceHover,
    onSecondaryContainer = OrbLightPalette.fgStrong,
    tertiary = OrbLightPalette.green,
    onTertiary = Color.White,
    tertiaryContainer = OrbLightPalette.surfaceHover,
    onTertiaryContainer = OrbLightPalette.green,
    error = OrbLightPalette.red,
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E8),
    onErrorContainer = OrbLightPalette.red,
    background = OrbLightPalette.bg,
    onBackground = OrbLightPalette.fgStrong,
    surface = OrbLightPalette.surface,
    onSurface = OrbLightPalette.fgStrong,
    surfaceVariant = OrbLightPalette.surfaceHover,
    onSurfaceVariant = OrbLightPalette.fgMuted,
    outline = OrbLightPalette.border,
    outlineVariant = OrbLightPalette.border
)

private val OrbTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.35).sp
    ),
    titleMedium = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    titleSmall = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.1).sp
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
        lineHeight = 18.sp,
        letterSpacing = (-0.1).sp
    ),
    labelMedium = TextStyle(
        fontFamily = OrbSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = OrbMonoFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun SideSystemTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = if (useDark) OrbDarkPalette else OrbLightPalette
    val colorScheme = if (useDark) OrbDarkColorScheme else OrbLightColorScheme
    CompositionLocalProvider(LocalOrbPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OrbTypography,
            content = content
        )
    }
}
