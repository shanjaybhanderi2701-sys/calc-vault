package com.appblish.calculatorvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors =
    lightColorScheme(
        primary = Accent,
        surface = NeutralSurfaceLight,
        background = NeutralSurfaceLight,
        onSurface = NeutralOnSurfaceLight,
        onBackground = NeutralOnSurfaceLight,
    )

private val DarkColors =
    darkColorScheme(
        primary = AccentDark,
        surface = NeutralSurfaceDark,
        background = NeutralSurfaceDark,
        onSurface = NeutralOnSurfaceDark,
        onBackground = NeutralOnSurfaceDark,
    )

/**
 * Single theming hook for the whole app. One accent, neutral canvas. Both the
 * calculator disguise and the vault render through this so they stay visually
 * indistinguishable until the vault content itself is shown.
 */
@Composable
fun CalculatorVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CalculatorVaultTypography,
        content = content,
    )
}
