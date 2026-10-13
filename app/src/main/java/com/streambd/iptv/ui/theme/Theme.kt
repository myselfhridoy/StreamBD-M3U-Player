package com.streambd.iptv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary          = Accent,
    onPrimary        = BgDark,
    background       = BgDark,
    onBackground     = TextPrimary,
    surface          = Surface1,
    onSurface        = TextPrimary,
    surfaceVariant   = Panel,
    onSurfaceVariant = TextMuted,
)

@Composable
fun StreamBdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = AppTypography, content = content)
}
