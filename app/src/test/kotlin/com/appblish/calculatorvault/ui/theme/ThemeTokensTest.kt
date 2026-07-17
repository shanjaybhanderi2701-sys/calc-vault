package com.appblish.calculatorvault.ui.theme

import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit coverage for the APP-525 theme token system: first-run defaults, the accent-token swap,
 * and the mode-driven neutral ramp. These are the invariants the DoD rests on — default Dark,
 * default Blue, a single accent that flows into every token, and legible on-accent ink.
 */
class ThemeTokensTest {
    @Test
    fun `first-run defaults are Dark mode and Blue accent`() {
        assertThat(ThemeMode.DEFAULT).isEqualTo(ThemeMode.DARK)
        assertThat(AccentColor.DEFAULT).isEqualTo(AccentColor.BLUE)
    }

    @Test
    fun `the chosen accent becomes the accent token in both modes`() {
        val dark = vaultColors(AccentColor.PURPLE, dark = true)
        val light = vaultColors(AccentColor.PURPLE, dark = false)
        assertThat(dark.accent).isEqualTo(AccentColor.PURPLE.swatch)
        assertThat(dark.accentPressed).isEqualTo(AccentColor.PURPLE.pressed)
        assertThat(light.accent).isEqualTo(AccentColor.PURPLE.swatch)
    }

    @Test
    fun `mode selects the neutral canvas ramp - dark differs from light`() {
        val dark = vaultColors(AccentColor.BLUE, dark = true)
        val light = vaultColors(AccentColor.BLUE, dark = false)
        // Dark canvas is near-black, light canvas is near-white — the accent is identical.
        assertThat(dark.canvas).isNotEqualTo(light.canvas)
        assertThat(dark.canvas.luminance()).isLessThan(light.canvas.luminance())
        assertThat(dark.accent).isEqualTo(light.accent)
    }

    @Test
    fun `on-accent ink is dark on bright swatches and white on saturated ones`() {
        // Amber is bright → dark ink; Blue is saturated → white ink. Keeps the check-mark /
        // label legible on every swatch. The token pulls the swatch's own onInk.
        val amber = vaultColors(AccentColor.AMBER, dark = true)
        val blue = vaultColors(AccentColor.BLUE, dark = true)
        assertThat(amber.onAccent).isEqualTo(AccentColor.AMBER.onInk)
        assertThat(blue.onAccent).isEqualTo(AccentColor.BLUE.onInk)
        assertThat(amber.onAccent.luminance()).isLessThan(0.5f)
        assertThat(blue.onAccent.luminance()).isGreaterThan(0.5f)
    }

    @Test
    fun `every palette entry has a distinct swatch and a name round-trips`() {
        val swatches = AccentColor.entries.map { it.swatch }
        assertThat(swatches).containsNoDuplicates()
        AccentColor.entries.forEach { accent ->
            assertThat(AccentColor.fromNameOrNull(accent.name)).isEqualTo(accent)
        }
        assertThat(AccentColor.fromNameOrNull("not-a-color")).isNull()
        assertThat(ThemeMode.fromNameOrNull("not-a-mode")).isNull()
    }

    @Test
    fun `controller swaps mode and accent live`() {
        ThemeController.apply(ThemeMode.DEFAULT, AccentColor.DEFAULT)
        ThemeController.selectAccent(AccentColor.TEAL)
        ThemeController.selectMode(ThemeMode.LIGHT)
        assertThat(ThemeController.accent).isEqualTo(AccentColor.TEAL)
        assertThat(ThemeController.mode).isEqualTo(ThemeMode.LIGHT)
        // Restore defaults so ordering between tests can't leak state.
        ThemeController.apply(ThemeMode.DEFAULT, AccentColor.DEFAULT)
    }
}
