package com.kirkouski.gtwake.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// GT-Alarm brand palette. Material You dynamic color is intentionally NOT used
// so the brand renders identically on every device.
//   primary   cyan    #009EDA
//   secondary indigo  #6373F2
//   tertiary  magenta #E058CE
//   error     red     #DC2626 — kept off the 6-hue brand palette on purpose so
//                               destructive / error UI still reads as danger.
// Success green #1EA52C and warning orange #DE6D23 have no Material slot; they
// live in the icon system (tools/icongen).

private val LightColors = lightColorScheme(
    primary = Color(0xFF009EDA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDE7F8),
    onPrimaryContainer = Color(0xFF00374D),
    secondary = Color(0xFF6373F2),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E2FF),
    onSecondaryContainer = Color(0xFF0B1166),
    tertiary = Color(0xFFE058CE),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD6F4),
    onTertiaryContainer = Color(0xFF38002F),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1B1720),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1B1720),
    surfaceVariant = Color(0xFFECE7F3),
    onSurfaceVariant = Color(0xFF50495C),
    outline = Color(0xFF81788A),
    outlineVariant = Color(0xFFE0D9E7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF007EAE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF004C6A),
    onPrimaryContainer = Color(0xFFBDE7F8),
    secondary = Color(0xFFBEC2FF),
    onSecondary = Color(0xFF1B2178),
    secondaryContainer = Color(0xFF3C46C4),
    onSecondaryContainer = Color(0xFFE0E2FF),
    tertiary = Color(0xFFF7ABE8),
    onTertiary = Color(0xFF5A004E),
    tertiaryContainer = Color(0xFFB23A9D),
    onTertiaryContainer = Color(0xFFFFD6F4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF020513),
    onBackground = Color(0xFFF1ECF7),
    surface = Color(0xFF1D1726),
    onSurface = Color(0xFFF1ECF7),
    surfaceVariant = Color(0xFF242031),
    onSurfaceVariant = Color(0xFFCFC5D8),
    outline = Color(0xFF8D8398),
    outlineVariant = Color(0xFF383145),
)

// Pastel gradient derived from brand palette — pale cyan (top-left) →
// pale pink (centre) → pale blue (bottom-right).
private val GtBackgroundBrushLight = Brush.linearGradient(
    0.00f to Color(0xFFECFAFE),  // pale cyan, 50% closer to white
    0.50f to Color(0xFFF5CCF0),  // pale pink  (tertiary #E058CE tint)
    1.00f to Color(0xFFD8DCFF),  // pale blue  (secondary #6373F2 tint)
    start = Offset.Zero,
    end = Offset.Infinite,
)

// Dark gradient: each stop is the corresponding brand colour at very low lightness.
// Mirrors the light gradient arc — cyan (primary, top-left) → indigo (secondary,
// centre) → magenta (tertiary, bottom-right).
private val GtBackgroundBrushDark = Brush.linearGradient(
    0.00f to Color(0xFF000817),  // near-black cyan
    0.52f to Color(0xFF26072F),  // visible dark magenta
    1.00f to Color(0xFF06071E),  // near-black indigo
    start = Offset.Zero,
    end = Offset.Infinite,
)

val GtFloatingButtonLight = Color(0xFFFFFFFF)

@Composable
fun gtBackgroundBrush(): Brush =
    if (isSystemInDarkTheme()) GtBackgroundBrushDark else GtBackgroundBrushLight

@Composable
fun GtAlarmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
