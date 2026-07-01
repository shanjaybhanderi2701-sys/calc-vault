package com.appblish.calculatorvault.settings

import androidx.compose.ui.graphics.Color

// Personalization + protection state surfaced on the Settings screens (Phase 5). This is the
// non-secret configuration layer that sits alongside the auth-package credential secrets: it
// decides how the disguise keypad looks, which unlock animation plays, and which hardening
// switches are armed. Persisted through SettingsStore.

/**
 * A keypad skin for the calculator disguise. Only [accent] and [keyShape] change — the
 * layout, digits, and secret-unlock behaviour stay identical so the cover never looks like
 * anything other than a calculator. The accent stays a single vivid hue per the taste
 * guide; skins swap *which* hue, never introducing a second accent on one screen.
 */
enum class KeypadSkin(
    val displayName: String,
    val accent: Color,
    val keyShape: KeySkinShape,
) {
    /** The default deck skin: vivid green, circular keys. */
    GREEN_CLASSIC("Classic Green", Color(0xFF22C55E), KeySkinShape.CIRCLE),

    /** Same circular keys, cool indigo accent. */
    INDIGO_NIGHT("Indigo Night", Color(0xFF6366F1), KeySkinShape.CIRCLE),

    /** Warm amber accent, rounded-square keys. */
    AMBER_DUSK("Amber Dusk", Color(0xFFF59E0B), KeySkinShape.ROUNDED),

    /** Monochrome graphite — the most inconspicuous, rounded-square keys. */
    GRAPHITE("Graphite", Color(0xFF9AA0A6), KeySkinShape.ROUNDED),
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
 */
enum class UnlockAnimation(
    val displayName: String,
    val description: String,
) {
    FADE("Fade", "A quiet cross-fade into the vault."),
    SLIDE_UP("Slide Up", "The vault rises over the calculator."),
    NONE("Instant", "No animation — the fastest, most discreet open."),
    ;

    companion object {
        val DEFAULT = FADE

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
)
