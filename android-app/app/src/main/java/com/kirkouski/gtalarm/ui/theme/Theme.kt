package com.kirkouski.gtalarm.ui.theme

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
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FC9EF),
    onPrimary = Color(0xFF00344A),
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
)

// Pastel gradient derived from brand palette — light indigo (top-left) →
// near-white (centre) → light cyan (bottom-right).
private val GtBackgroundBrushLight = Brush.linearGradient(
    0.00f to Color(0xFFF0F1FF),
    0.45f to Color(0xFFFAFAFF),
    1.00f to Color(0xFFE5F7FD),
    start = Offset.Zero,
    end = Offset.Infinite,
)

// Deep navy/indigo gradient for dark theme — all stops derived from the same
// brand colours at very low lightness.
private val GtBackgroundBrushDark = Brush.linearGradient(
    0.00f to Color(0xFF0F1235),
    0.45f to Color(0xFF0D1628),
    1.00f to Color(0xFF12102E),
    start = Offset.Zero,
    end = Offset.Infinite,
)

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
