package com.vicinityprobe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5D6E),
    secondary = Color(0xFF4A6D7C),
    tertiary = Color(0xFF6E8B3D),
    background = Color(0xFFF7FAFB),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    secondary = Color(0xFF8FB7C7),
    tertiary = Color(0xFFB1CC7E),
    background = Color(0xFF0B1420),
    surface = Color(0xFF12202F),
)

@Composable
fun VicinityProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
