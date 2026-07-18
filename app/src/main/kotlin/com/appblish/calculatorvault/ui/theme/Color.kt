package com.appblish.calculatorvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Token palette (APP-525). The accent is a single design token supplied by the
// user's chosen [AccentColor]; the neutral canvas/surface/text ramps come in a
// dark and a light variant selected by the active [ThemeMode]. RED stays reserved
// exclusively for destructive actions in both themes. No hard-coded accent hue
// lives here anymore — the accent flows in from [vaultColors].
// ---------------------------------------------------------------------------

/** Destructive-only. Delete, permanent-remove, break-in warnings. Never decorative. */
private val DestructiveRed = Color(0xFFEF4444)
private val OnDestructive = Color(0xFFFFFFFF)

// Neutral DARK ramp — near-black canvas so the disguise and vault stay indistinguishable.
private val CanvasBlack = Color(0xFF0D0F12)
private val SurfaceDark = Color(0xFF15181D)
private val SurfaceVariantDark = Color(0xFF1F242B)
private val DividerDark = Color(0xFF2A2F37)
private val TextPrimaryDark = Color(0xFFFFFFFF)
private val TextSecondaryDark = Color(0xFF9AA0A6)
private val TextDisabledDark = Color(0xFF5F656D)

// Neutral LIGHT ramp — a clean near-white surface set that reads well under any accent.
private val CanvasLight = Color(0xFFF7F8FA)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceVariantLight = Color(0xFFEFF1F4)
private val DividerLight = Color(0xFFE2E5EA)
private val TextPrimaryLight = Color(0xFF1A1C1E)
private val TextSecondaryLight = Color(0xFF5F656D)
private val TextDisabledLight = Color(0xFFA0A4AA)

/**
 * Semantic color tokens. Screens and components read these instead of raw hex so the palette
 * — accent AND mode — can shift in one place. Exposed through [LocalVaultColors]; grab it with
 * `VaultTheme.colors`.
 */
@Immutable
data class VaultColors(
    val accent: Color,
    val accentPressed: Color,
    /** Accent @12% — the single selection/active container fill used everywhere (spec §C). */
    val accentContainer: Color,
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

/**
 * Build the semantic token set for a given [accent] swatch and light/dark [dark] mode. This is
 * the single place accent + neutrals are combined, so every screen recolors from one call. The
 * on-accent foreground comes from the swatch's own [AccentColor.onInk] so text/icons on the
 * accent stay legible on every swatch.
 */
fun vaultColors(
    accent: AccentColor,
    dark: Boolean,
): VaultColors =
    VaultColors(
        accent = accent.swatch,
        accentPressed = accent.pressed,
        accentContainer = accent.swatch.copy(alpha = 0.12f),
        onAccent = accent.onInk,
        destructive = DestructiveRed,
        onDestructive = OnDestructive,
        canvas = if (dark) CanvasBlack else CanvasLight,
        surface = if (dark) SurfaceDark else SurfaceLight,
        surfaceVariant = if (dark) SurfaceVariantDark else SurfaceVariantLight,
        divider = if (dark) DividerDark else DividerLight,
        textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight,
        textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight,
        textDisabled = if (dark) TextDisabledDark else TextDisabledLight,
    )

/**
 * Provided by [CalculatorVaultTheme]; defaults to the first-run palette (Blue accent, dark
 * mode) so any composable read outside the theme still resolves to sensible tokens.
 */
val LocalVaultColors = staticCompositionLocalOf { vaultColors(AccentColor.DEFAULT, dark = true) }
