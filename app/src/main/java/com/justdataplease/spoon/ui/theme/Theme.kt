package com.justdataplease.spoon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Paprika,
    onPrimary = Color.White,
    primaryContainer = Peach,
    onPrimaryContainer = Color(0xFF3A0A02),
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = Mint,
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Gold,
    background = Cream,
    onBackground = Ink,
    surface = Color(0xFFFFFBF7),
    onSurface = Ink,
    surfaceVariant = Oat,
    outline = Color(0xFF89736A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A2),
    onPrimary = Color(0xFF5B190B),
    primaryContainer = Color(0xFF7C2C1B),
    secondary = Color(0xFFA0D2C5),
    onSecondary = Color(0xFF07372F),
    secondaryContainer = Color(0xFF1C5147),
    background = Night,
    onBackground = Color(0xFFF2DFD6),
    surface = NightSurface,
    onSurface = Color(0xFFF2DFD6),
    surfaceVariant = Color(0xFF51443E),
    outline = Color(0xFFD2BBB1),
)

@Composable
fun SpoonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // A fixed food-inspired palette gives the app a recognisable identity on every device.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SpoonTypography,
        shapes = SpoonShapes,
        content = content,
    )
}
