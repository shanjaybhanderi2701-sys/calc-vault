package com.appblish.calculatorvault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Corner-radius tokens from the deck: fully-round pills, rounded cards, circular
 * calculator keys. Grab it with `VaultTheme.shapes`.
 */
@Immutable
data class VaultShapes(
    /** Pill CTA / segmented switch — fully rounded. */
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
    /** Category card, list row, modal container. */
    val card: RoundedCornerShape = RoundedCornerShape(16.dp),
    /** Media thumbnail / small tile. */
    val thumbnail: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** Chips / small badges. */
    val chip: RoundedCornerShape = RoundedCornerShape(8.dp),
)

/** Provided by [CalculatorVaultTheme]. */
val LocalVaultShapes = staticCompositionLocalOf { VaultShapes() }
