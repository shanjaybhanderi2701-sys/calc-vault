package com.appblish.calculatorvault.settings

import androidx.compose.ui.graphics.Color

// Personalization + protection state surfaced on the Settings screens (Phase 5). This is the
// non-secret configuration layer that sits alongside the auth-package credential secrets: it
// decides how the disguise keypad looks, which unlock animation plays, and which hardening
// switches are armed. Persisted through SettingsStore.

/**
 * A keypad skin for the calculator disguise. Only the accent *tone* and [keyShape] change —
 * the layout, digits, and secret-unlock behaviour stay identical so the cover never looks
 * like anything other than a calculator. Per the standing board rule the accent stays a
 * single **green** identity hue on every screen; skins vary the green tone and key
 * silhouette only, never introducing a competing colour.
 */
enum class KeypadSkin(
    val displayName: String,
    val accent: Color,
    val keyShape: KeySkinShape,
) {
    /** The default deck skin: vivid green, circular keys. */
    GREEN_CLASSIC("Classic Green", Color(0xFF22C55E), KeySkinShape.CIRCLE),

    /** Deeper emerald green, rounded-square keys. */
    EMERALD_ROUNDED("Emerald", Color(0xFF16A34A), KeySkinShape.ROUNDED),

    /** Darkest forest green — the most inconspicuous, rounded-square keys. */
    FOREST_DEEP("Forest", Color(0xFF15803D), KeySkinShape.ROUNDED),

    /** Bright lime green, circular keys. */
    LIME_BRIGHT("Lime", Color(0xFF4ADE80), KeySkinShape.CIRCLE),
    ;

    companion object {
        val DEFAULT = GREEN_CLASSIC

        fun fromNameOrNull(name: String?): KeypadSkin? = entries.firstOrNull { it.name == name }
    }
}

/** Key silhouette for a [KeypadSkin]. */
enum class KeySkinShape {
    CIRCLE,
    ROUNDED,
}

/**
 * The transition played when a correct PIN opens a vault. Cosmetic only; the resolve
 * decision has already been made by [com.appblish.calculatorvault.auth.CredentialStore].
 * The six named options match the deck's Theme → *Unlock animation* list exactly — four
 * directional slides plus fade-in/fade-out. No option is invented outside the deck.
 */
enum class UnlockAnimation(
    val displayName: String,
    val description: String,
) {
    SLIDE_TOP("Slide Top", "The vault slides up from the bottom of the screen."),
    SLIDE_DOWN("Slide Down", "The vault drops in from the top of the screen."),
    SLIDE_RIGHT("Slide Right", "The vault slides in from the left edge."),
    SLIDE_LEFT("Slide Left", "The vault slides in from the right edge."),
    FADE_IN("Fade In", "The vault fades gently into view."),
    FADE_OUT("Fade Out", "The calculator fades out as the vault appears."),
    ;

    companion object {
        val DEFAULT = FADE_IN

        fun fromNameOrNull(name: String?): UnlockAnimation? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The full snapshot of user settings. Immutable value read by the Settings/Theme screens
 * and by the calculator disguise (for the live skin). Defaults match the board deck.
 */
data class VaultSettings(
    val keypadSkin: KeypadSkin = KeypadSkin.DEFAULT,
    val unlockAnimation: UnlockAnimation = UnlockAnimation.DEFAULT,
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
