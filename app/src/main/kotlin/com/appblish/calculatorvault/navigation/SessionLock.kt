package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.VaultSession

/**
 * The vault re-lock policy (APP-205, QA fail from APP-203). Matching the reference app
 * xlock, an unlocked vault must NEVER survive the app being backgrounded: the moment the
 * whole app goes to the background the in-memory session (passphrase + data key) is
 * forgotten and the spine is reset behind the calculator disguise, so returning to the
 * foreground always lands on the calculator and requires the PIN to be re-entered.
 *
 * The wiring lives in [VaultNavHost], driven by `ProcessLifecycleOwner`'s `ON_STOP`
 * (whole-app background — it does not fire for in-app permission / delete-consent dialogs,
 * which only pause the activity, nor for configuration changes). This object holds the two
 * decisions that are pure and therefore unit-testable in isolation from Compose/lifecycle.
 */
internal object SessionLock {
    /**
     * Routes that are *in front of* the unlocked vault — the disguise/auth spine and the
     * point-of-need storage primer. Backgrounding on any of these must NOT clear the session
     * or navigate:
     *  - the calculator / gate / onboarding / forgot-password expose no vault content; and
     *  - the storage primer sends the user to full-screen system Settings to grant All Files
     *    Access and finishes the unlock on return via its resume re-check — clearing the
     *    session here would strand that grant (see [VaultNavHost] `enterVault`).
     * Every other route is behind the unlocked vault and must be re-locked.
     */
    private val PRE_VAULT_SURFACES =
        setOf(
            VaultDestinations.GATE,
            VaultDestinations.ONBOARDING,
            VaultDestinations.CALCULATOR,
            VaultDestinations.FORGOT_PASSWORD,
            VaultDestinations.STORAGE_PRIMER,
        )

    /**
     * True when [route] is behind the unlocked vault and so must be re-locked on background.
     * A null route (graph not yet resolved) is treated as pre-vault: nothing to lock.
     */
    fun isVaultSurface(route: String?): Boolean = route != null && route !in PRE_VAULT_SURFACES

    /**
     * Forget the in-memory session and drop the data key + cached content so a backgrounded
     * vault cannot be resumed. Safe to call repeatedly. Callers gate this on
     * [isVaultSurface] so the storage-primer grant round-trip is never disturbed.
     */
    fun relock() {
        VaultSession.clear()
        VaultGraph.contentRepository.lock()
    }
}
