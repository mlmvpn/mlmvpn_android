package com.mlmvpn.scanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============================================================
// Color Palette (matches the React prototype exactly)
// ============================================================
val BgDark       = Color(0xFF121212)
val SurfaceDark  = Color(0xFF202124)
val BorderDark   = Color(0xFF3C4043)
val Primary      = Color(0xFF8AB4F8)
val PrimaryLight = Color(0xFFAECBFA)
val TextPrimary  = Color(0xFFE8EAED)
val TextMuted    = Color(0xFF9AA0A6)
val TextDim      = Color(0xFF5F6368)
val GreenOk      = Color(0xFF81C995)
val RedError     = Color(0xFFF28B82)
val YellowWarn   = Color(0xFFFDE293)
val ConnectedRed = Color(0xFFCF6679)

private val DarkColorScheme = darkColorScheme(
    primary            = Primary,
    onPrimary          = BgDark,
    background         = BgDark,
    surface            = SurfaceDark,
    onBackground       = TextPrimary,
    onSurface          = TextPrimary,
    outline            = BorderDark,
    secondaryContainer = BorderDark,
)

@Composable
fun MlmVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
