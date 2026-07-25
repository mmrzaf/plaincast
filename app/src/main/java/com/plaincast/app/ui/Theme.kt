package com.plaincast.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PlainCastPurple = Color(0xFF6D49AF)
private val PlainCastPurpleLight = Color(0xFFCDB7FF)
private val PlainCastPurpleContainer = Color(0xFFEADDFF)
private val PlainCastPurpleDarkContainer = Color(0xFF4E3286)

private val LightColors = lightColorScheme(
    primary = PlainCastPurple,
    onPrimary = Color.White,
    primaryContainer = PlainCastPurpleContainer,
    onPrimaryContainer = Color(0xFF21113D),
    secondary = Color(0xFF665A73),
)

private val DarkColors = darkColorScheme(
    primary = PlainCastPurpleLight,
    onPrimary = Color(0xFF382064),
    primaryContainer = PlainCastPurpleDarkContainer,
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFD0C1DB),
)

@Composable
fun PlainCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
