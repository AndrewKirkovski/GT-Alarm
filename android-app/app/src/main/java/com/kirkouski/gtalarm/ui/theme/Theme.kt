package com.kirkouski.gtalarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF6A3DF0),
    onPrimary = Color.White,
    secondary = Color(0xFF4E4056),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBA6FF),
    onPrimary = Color.Black,
    secondary = Color(0xFFCBB8D6),
)

// Note: MaterialExpressiveTheme + ExperimentalMaterial3ExpressiveApi are still
// declared `internal` in androidx.compose.material3 1.4.0 (the Compose BOM
// 2026.04.01 ceiling) AND in 1.5.0-alpha18 (latest as of 2026-04). The
// `material3-expressive` artifact is not published. Switch to it when the
// symbols go public. AC item: tracked under "MaterialExpressiveTheme" gap.
@Composable
fun GtAlarmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
