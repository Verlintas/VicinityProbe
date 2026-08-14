/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 主题模式:跟随系统 / 强制浅色 / 强制深色 */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromId(id: String): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

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

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }

object ThemePrefs {
    private const val PREFS = "theme"
    private const val KEY_MODE = "mode"

    fun mode(context: Context): ThemeMode {
        return try {
            ThemeMode.fromId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, ThemeMode.SYSTEM.id) ?: ThemeMode.SYSTEM.id)
        } catch (_: Throwable) {
            ThemeMode.SYSTEM
        }
    }

    fun setMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.id).apply()
    }
}

@Composable
fun VicinityProbeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = ThemePrefs.mode(context)
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Android 12+ 使用 Material You 动态取色,低版本回退到品牌配色
    val colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        try {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } catch (_: Throwable) {
            if (dark) DarkColors else LightColors
        }
    } else {
        if (dark) DarkColors else LightColors
    }
    CompositionLocalProvider(LocalThemeMode provides mode) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
