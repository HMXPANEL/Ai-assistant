package com.voicecontrol.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    secondary = Color(0xFF008069),
    background = LightBackground,
    surface = LightSurface,
)

object Theme {
    @Composable
    fun VoiceControl(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
