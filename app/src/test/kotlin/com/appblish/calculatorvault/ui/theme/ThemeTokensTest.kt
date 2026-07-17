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
    fun `selection container is accent at 12 percent and recolors with the accent (APP-529)`() {
        // APP-529 redesign: every selection/active container (grid tile overlay, pin badge,
        // sort segment, cover ring container) now reads `colors.accentContainer` instead of the
        // literal blue that used to live in VaultGridTokens. The container must be the live
        // accent at 12% opacity so switching accents recolors it — no drift, no hard-coded hue.
        val blue = vaultColors(AccentColor.BLUE, dark = true)
        val rose = vaultColors(AccentColor.ROSE, dark = true)
        assertThat(blue.accentContainer).isEqualTo(AccentColor.BLUE.swatch.copy(alpha = 0.12f))
        assertThat(rose.accentContainer).isEqualTo(AccentColor.ROSE.swatch.copy(alpha = 0.12f))
        // Same hue as the accent, distinct alpha, and it moves when the accent moves.
        assertThat(blue.accentContainer).isNotEqualTo(rose.accentContainer)
        // Compose quantizes alpha to 8 bits (0.12 → 31/255 ≈ 0.1216), so allow that step.
        assertThat(blue.accentContainer.alpha).isWithin(0.004f).of(0.12f)
    }

    @Test
    fun `accent container is identical across modes - selection reads the same in dark and light (APP-529)`() {
        // The accent (and therefore its 12% container) does not depend on the neutral ramp,
        // so a selected tile looks accent-consistent whether the user is in Dark or Light.
        val dark = vaultColors(AccentColor.INDIGO, dark = true)
        val light = vaultColors(AccentColor.INDIGO, dark = false)
        assertThat(dark.accentContainer).isEqualTo(light.accentContainer)
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
