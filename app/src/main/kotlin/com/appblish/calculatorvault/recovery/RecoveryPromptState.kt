package com.appblish.calculatorvault.recovery

/**
 * Session-scoped, in-memory nag state for the recovery setup prompts (PIN Recovery spec §4,
 * W0 screens 01 + 07). Deliberately NOT persisted: the warning banner's "Later" hides it
 * for the current unlocked session only and it returns on the next launch (W0 07), and the
 * one-time setup intro sheet (W0 01) shows at most once per session. Both flags are reset by
 * [com.appblish.calculatorvault.vault.VaultSession.clear] on every re-lock, so a fresh
 * unlock is a fresh "next launch".
 *
 * "Recovery configured" itself is NOT tracked here — it is the durable, survive-uninstall
 * truth read from the key file via [RecoveryManager.isConfigured]; once recovery is set up,
 * neither prompt is eligible regardless of these flags.
 */
object RecoveryPromptState {
    /** Set by tapping "Later" on the grid banner (W0 07) — hides it until the next unlock. */
    @Volatile
    var bannerDismissedThisSession: Boolean = false

    /** Set once the setup intro sheet (W0 01) has been offered this session (shown at most once). */
    @Volatile
    var introOfferedThisSession: Boolean = false

    /** Forget both flags — called from [com.appblish.calculatorvault.vault.VaultSession.clear]. */
    fun reset() {
        bannerDismissedThisSession = false
        introOfferedThisSession = false
    }
}
