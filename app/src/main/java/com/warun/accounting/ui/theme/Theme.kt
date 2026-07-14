package com.warun.accounting.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2F5BE8),
    onPrimary = Color.White,
    secondary = Color(0xFF21A366),
    tertiary = Color(0xFFF4A62A),
    background = Color(0xFFF5F7FB),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    outline = Color(0xFFE5E7EB)
)

@Composable
fun WarunTheme(content: @Composable () -> Unit) {
    val ignored = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = LightColors,
        typography = WarunTypography,
        content = content
    )
}
