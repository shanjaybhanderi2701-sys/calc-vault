package com.appblish.calculatorvault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

// The vault is intentionally dark-only: the calculator disguise and the vault both
// render on the near-black canvas so they stay visually indistinguishable. Material3
// components inherit these mapped tokens; bespoke components read VaultTheme directly.
private val DarkColors =
    darkColorScheme(
        primary = AccentGreen,
        onPrimary = OnAccent,
        secondary = AccentGreen,
        onSecondary = OnAccent,
        error = DestructiveRed,
        onError = OnDestructive,
        background = CanvasBlack,
        onBackground = TextPrimaryDark,
        surface = SurfaceDark,
        onSurface = TextPrimaryDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = TextSecondaryDark,
        outline = DividerDark,
    )

/**
 * Single theming hook for the whole app. Installs the Material3 color/type scheme
 * plus the bespoke [VaultColors] / [VaultSpacing] / [VaultShapes] token sets.
 */
@Composable
fun CalculatorVaultTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalVaultColors provides DarkVaultColors,
        LocalVaultSpacing provides VaultSpacing(),
        LocalVaultShapes provides VaultShapes(),
    ) {
        MaterialTheme(
            colorScheme = DarkColors,
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
