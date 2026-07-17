package com.appblish.calculatorvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/**
 * Map the bespoke [VaultColors] token set onto a Material3 [ColorScheme] so Material components
 * (dialogs, switches, text fields) inherit the same accent + surfaces as the bespoke ones. Built
 * from the single accent token, so a swatch change recolors Material and bespoke UI alike.
 */
private fun materialSchemeFrom(
    colors: VaultColors,
    dark: Boolean,
): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        error = colors.destructive,
        onError = colors.onDestructive,
        background = colors.canvas,
        onBackground = colors.textPrimary,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceVariant,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.divider,
    )
}

/**
 * Single theming hook for the whole app (APP-525). Reads the live [ThemeMode] + [AccentColor]
 * from [ThemeController], resolves light/dark (following the OS for [ThemeMode.SYSTEM]), and
 * installs the Material3 color/type scheme plus the bespoke [VaultColors] / [VaultSpacing] /
 * [VaultShapes] token sets. Because the mode/accent are Compose state, tapping a swatch or
 * changing the mode recomposes this and recolors every screen instantly.
 *
 * [mode] / [accent] can be overridden (previews, screenshot tests); they default to the live
 * controller state.
 */
@Composable
fun CalculatorVaultTheme(
    mode: ThemeMode = ThemeController.mode,
    accent: AccentColor = ThemeController.accent,
    content: @Composable () -> Unit,
) {
    val dark =
        when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
    val vaultColors = remember(accent, dark) { vaultColors(accent, dark) }
    val colorScheme = remember(vaultColors, dark) { materialSchemeFrom(vaultColors, dark) }

    CompositionLocalProvider(
        LocalVaultColors provides vaultColors,
        LocalVaultSpacing provides VaultSpacing(),
        LocalVaultShapes provides VaultShapes(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CalculatorVaultTypography,
            content = content,
        )
    }
}

/** Ergonomic accessor for the bespoke token sets, mirroring [MaterialTheme]. */
object VaultTheme {
    val colors: VaultColors
        @Composable
        @ReadOnlyComposable
        get() = LocalVaultColors.current

    val spacing: VaultSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalVaultSpacing.current

    val shapes: VaultShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalVaultShapes.current

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography
}
