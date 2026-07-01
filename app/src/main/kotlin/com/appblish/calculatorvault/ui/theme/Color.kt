package com.appblish.calculatorvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Raw palette — read from the board's 65-flow deck (design-spec, APP-142).
// The vault is dark-first: near-black canvas, one vivid GREEN accent, and RED
// reserved exclusively for destructive actions. No other brand hues.
// ---------------------------------------------------------------------------

/** The single brand accent. Primary CTAs, active nav, toggles, selection, `=`/`AC`, FABs. */
internal val AccentGreen = Color(0xFF22C55E)
internal val AccentGreenPressed = Color(0xFF16A34A)

/** Destructive-only. Delete, permanent-remove, break-in warnings. Never decorative. */
internal val DestructiveRed = Color(0xFFEF4444)

// Neutral canvas.
internal val CanvasBlack = Color(0xFF0D0F12)
internal val SurfaceDark = Color(0xFF15181D)
internal val SurfaceVariantDark = Color(0xFF1F242B)
internal val DividerDark = Color(0xFF2A2F37)

internal val TextPrimaryDark = Color(0xFFFFFFFF)
internal val TextSecondaryDark = Color(0xFF9AA0A6)
internal val TextDisabledDark = Color(0xFF5F656D)

// On-accent / on-destructive foreground (dark ink reads best on the vivid green).
internal val OnAccent = Color(0xFF06210F)
internal val OnDestructive = Color(0xFFFFFFFF)

/**
 * Semantic color tokens. Screens and components read these instead of raw hex so
 * the palette can shift in one place. Exposed through [LocalVaultColors]; grab it
 * with `VaultTheme.colors`.
 */
@Immutable
data class VaultColors(
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val destructive: Color,
    val onDestructive: Color,
    val canvas: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
)

internal val DarkVaultColors =
    VaultColors(
        accent = AccentGreen,
        accentPressed = AccentGreenPressed,
        onAccent = OnAccent,
        destructive = DestructiveRed,
        onDestructive = OnDestructive,
        canvas = CanvasBlack,
        surface = SurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        divider = DividerDark,
        textPrimary = TextPrimaryDark,
        textSecondary = TextSecondaryDark,
        textDisabled = TextDisabledDark,
    )

/** Provided by [CalculatorVaultTheme]; defaults to the dark palette. */
val LocalVaultColors = staticCompositionLocalOf { DarkVaultColors }
