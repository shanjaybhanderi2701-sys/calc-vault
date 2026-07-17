package com.appblish.calculatorvault.settings

import com.appblish.calculatorvault.ui.theme.AccentColor
import com.appblish.calculatorvault.ui.theme.ThemeMode

// Personalization + protection state surfaced on the Settings screens (Phase 5). This is the
// non-secret configuration layer that sits alongside the auth-package credential secrets: it
// decides which hardening switches are armed. Persisted through SettingsStore. (APP-528
// removed the calculator-theming layer — keypad skin + unlock animation — entirely.)

/**
 * The full snapshot of user settings. Immutable value read by the Settings screens. Defaults
 * match the board deck.
 */
data class VaultSettings(
    /** App-wide light/dark selection (APP-525 §1.1). Default Dark on first run. */
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    /** App-wide accent token (APP-525 §1.2). Default Blue; recolors the whole app when changed. */
    val accentColor: AccentColor = AccentColor.DEFAULT,
    /** Capture a front-camera intruder selfie after repeated wrong PINs. */
    val breakInAlertsEnabled: Boolean = true,
    /** Offer decoy (fake) PIN spaces under duress. */
    val fakePasswordEnabled: Boolean = true,
    /** Device-admin lock so the app cannot be uninstalled without the PIN. */
    val preventUninstallEnabled: Boolean = false,
    /** Show a plain "Calculator" launcher name/icon instead of the branded one. */
    val disguiseIconEnabled: Boolean = false,
    /** Require the unlock PIN again whenever the app returns to the foreground. */
    val relockOnBackgroundEnabled: Boolean = true,
    /** Randomise the calculator keypad digit positions on each unlock (anti shoulder-surf). */
    val shufflePinPadEnabled: Boolean = false,
    /** Vibrate the device on a wrong PIN entry. */
    val incorrectVibrationEnabled: Boolean = true,
    /**
     * Hide the app from the recents/app-switcher list entirely. **Off by default** (spec
     * §10, APP-225): a calculator that vanishes from recents looks *more* suspicious, not
     * less — `FLAG_SECURE` already blanks the recents preview, which is the primary
     * privacy layer. Exposed as an opt-in Settings toggle.
     */
    val hideFromRecentsEnabled: Boolean = false,
    /**
     * Allow screenshots + screen-recording of vault content. **Off by default** for privacy
     * (PIN Recovery W4 / spec §6): when off, the release build applies `FLAG_SECURE`, which
     * blocks screenshots and blanks the recents/app-switcher preview. Turning it on removes
     * `FLAG_SECURE` so screenshots work (and vault content may then appear in the recents
     * preview). Coexists with the debug-only `calcvault_allow_screenshots` capture gate
     * (APP-233): either one allowing screenshots is enough to drop `FLAG_SECURE`.
     */
    val allowScreenshotsEnabled: Boolean = false,
    /** The app language chosen at onboarding / in Settings ("Default" = follow system). */
    val appLanguage: String = "Default",
)
