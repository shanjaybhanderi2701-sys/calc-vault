package com.appblish.calculatorvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 4dp spacing scale from the deck. Components space themselves from these
 * tokens rather than hardcoding dp so rhythm stays consistent screen-to-screen.
 * Grab it with `VaultTheme.spacing`.
 */
@Immutable
data class VaultSpacing(
    /** 4dp — hairline gaps, icon-to-label. */
    val xs: Dp = 4.dp,
    /** 8dp — intra-component padding. */
    val sm: Dp = 8.dp,
    /** 12dp — list-row vertical padding. */
    val md: Dp = 12.dp,
    /** 16dp — default screen gutter. */
    val lg: Dp = 16.dp,
    /** 24dp — section separation. */
    val xl: Dp = 24.dp,
    /** 32dp — large blocks / empty-state breathing room. */
    val xxl: Dp = 32.dp,
    /** Standard screen edge gutter. */
    val screenGutter: Dp = 16.dp,
    /** Minimum tap target (accessibility). */
    val touchTarget: Dp = 48.dp,
)

/** Provided by [CalculatorVaultTheme]. */
val LocalVaultSpacing = staticCompositionLocalOf { VaultSpacing() }
