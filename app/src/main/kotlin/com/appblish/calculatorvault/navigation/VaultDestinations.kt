package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.auth.VaultKind

/**
 * Route table for the Phase 1 auth spine, kept in one place so the disguise boundary is
 * easy to audit. Flow: `gate` decides first-run vs returning → either `onboarding` or the
 * `calculator` disguise. From the calculator a resolved PIN opens `unlocked/{kind}`, and
 * the hidden recovery gesture opens `forgot_password`; the real vault can reach the
 * `fake_password` manager.
 */
internal object VaultDestinations {
    const val GATE = "gate"
    const val ONBOARDING = "onboarding"
    const val CALCULATOR = "calculator"
    const val FORGOT_PASSWORD = "forgot_password"
    const val FAKE_PASSWORD = "fake_password"
    const val SETTINGS = "settings"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_CHANGE_PIN = "settings/change_pin"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_BACKUP = "settings/backup"
    const val UNLOCKED_ARG = "kind"
    const val UNLOCKED_ROUTE = "unlocked/{$UNLOCKED_ARG}"

    /** Build the concrete `unlocked/…` route for the vault a PIN opened. */
    fun unlockedRoute(kind: VaultKind): String =
        when (kind) {
            VaultKind.Real -> "unlocked/real"
            is VaultKind.Decoy -> "unlocked/decoy_${kind.slot}"
        }

    /** Inverse of [unlockedRoute]: decode the nav arg back into a [VaultKind]. */
    fun parseKind(arg: String?): VaultKind =
        when {
            arg == null || arg == "real" -> VaultKind.Real
            arg.startsWith("decoy_") -> VaultKind.Decoy(arg.removePrefix("decoy_").toIntOrNull() ?: 0)
            else -> VaultKind.Real
        }
}
