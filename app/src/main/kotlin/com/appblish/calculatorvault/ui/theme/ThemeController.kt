package com.appblish.calculatorvault.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide, reactive source of truth for the live theme selection (APP-525). Holds the
 * current [ThemeMode] and [AccentColor] as Compose state so that when the user taps a swatch or
 * flips the mode, every composition that reads them through [CalculatorVaultTheme] recomposes
 * and recolors **instantly** — no activity restart, no theme flash.
 *
 * Persistence is owned elsewhere: the settings store is the durable record, and
 * [com.appblish.calculatorvault.settings.SettingsGraph.warmCaches] calls [apply] at app startup
 * to hydrate this holder from the persisted values. The Appearance screen's view model updates
 * this holder immediately (for the instant recolor) and writes through to the store in parallel.
 *
 * Defaults are the first-run choices (Dark + Blue) so the app is correctly themed before the
 * async store read completes; because Dark is the default, there is no light→dark flash.
 */
object ThemeController {
    var mode: ThemeMode by mutableStateOf(ThemeMode.DEFAULT)
        private set

    var accent: AccentColor by mutableStateOf(AccentColor.DEFAULT)
        private set

    /** Hydrate both selections at once (startup warm from the persisted settings). */
    fun apply(
        mode: ThemeMode,
        accent: AccentColor,
    ) {
        this.mode = mode
        this.accent = accent
    }

    /** Live mode change — recolors the app on the next frame. */
    fun selectMode(mode: ThemeMode) {
        this.mode = mode
    }

    /** Live accent change — recolors the app on the next frame. */
    fun selectAccent(accent: AccentColor) {
        this.accent = accent
    }
}
