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
 * which only pause the activity, nor for configuration changes). This object holds the
 * decisions that are pure and therefore unit-testable in isolation from Compose/lifecycle.
 */
internal object SessionLock {
    /**
     * Routes that are *in front of* the unlocked vault — the disguise/auth spine. They
     * expose no vault content, so backgrounding on them must NOT clear the session or
     * navigate. Every other route is behind the unlocked vault and must be re-locked.
     */
    private val PRE_VAULT_SURFACES =
        setOf(
            VaultDestinations.GATE,
            VaultDestinations.ONBOARDING,
            VaultDestinations.CALCULATOR,
        )

    /**
     * One-shot suppression for the All-Files-Access grant round-trip (spec §5, design call
     * D-2 on APP-224): the primer bottom sheet sends the user to full-screen **system
     * Settings** to flip the grant, which backgrounds the app and would otherwise re-lock
     * the vault and strand the "return straight into the tapped category" flow. The primer
     * arms this immediately before launching the system intent; the very next ON_STOP
     * consumes it instead of re-locking. It never survives more than one background event.
     */
    @Volatile
    private var suppressNextRelock: Boolean = false

    /** Arm the one-shot re-lock suppression for a permission grant round-trip. */
    fun beginGrantRoundTrip() {
        suppressNextRelock = true
    }

    /** Consume the one-shot suppression; true means "skip this re-lock". */
    fun consumeGrantRoundTrip(): Boolean {
        val suppressed = suppressNextRelock
        suppressNextRelock = false
        return suppressed
    }

    /**
     * True when [route] is behind the unlocked vault and so must be re-locked on background.
     * A null route (graph not yet resolved) is treated as pre-vault: nothing to lock.
     */
    fun isVaultSurface(route: String?): Boolean = route != null && route !in PRE_VAULT_SURFACES

    /**
     * Forget the in-memory session and drop the data key + cached content so a backgrounded
     * vault cannot be resumed. Safe to call repeatedly. Callers gate this on
     * [isVaultSurface].
     */
    fun relock() {
        VaultSession.clear()
        VaultGraph.contentRepository.lock()
    }
}
