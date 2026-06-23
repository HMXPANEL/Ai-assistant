package com.voicecontrol.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.isSystemInDarkTheme

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    primaryContainer = Color(0xFF005C4B),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF111B21),
    surface = Color(0xFF202C33),
    onPrimary = Color.Black,
    onSurface = Color.White,
    onBackground = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF008069),
    primaryContainer = Color(0xFF005C4B),
    secondary = Color(0xFF00695C),
    background = Color(0xFFF0F2F5),
    surface = Color.White,
    onPrimary = Color.White,
    onSurface = Color.Black,
    onBackground = Color.Black
)

@Composable
fun VoiceControl(
    darkTheme: Boolean = isSystemInDarkTheme(LocalContext.current),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}