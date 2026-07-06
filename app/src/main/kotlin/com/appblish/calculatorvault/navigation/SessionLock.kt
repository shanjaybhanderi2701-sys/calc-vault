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
     * How long an armed grant round-trip stays valid (APP-225 board finding P1b). A real
     * primer trip through system Settings takes seconds; a flag that was armed but never
     * consumed (grant intent failed to resolve, user swiped the app away mid-trip, …) must
     * NOT suppress a later, unrelated backgrounding — that would let Recents resume an
     * unlocked vault without the PIN. Two minutes is generous for the round-trip and far
     * too short to survive to a plausible "come back later" backgrounding.
     */
    private const val GRANT_ROUND_TRIP_EXPIRY_MS: Long = 2 * 60 * 1000L

    /**
     * One-shot suppression for the All-Files-Access grant round-trip (spec §5, design call
     * D-2 on APP-224): the primer bottom sheet sends the user to full-screen **system
     * Settings** to flip the grant, which backgrounds the app and would otherwise re-lock
     * the vault and strand the "return straight into the tapped category" flow. The primer
     * arms this immediately before launching the system intent; the very next ON_STOP
     * consumes it instead of re-locking. It never survives more than one background event,
     * and it auto-expires after [GRANT_ROUND_TRIP_EXPIRY_MS] even if never consumed.
     * `null` means "not armed"; otherwise it holds the arming timestamp.
     */
    @Volatile
    private var grantRoundTripArmedAtMs: Long? = null

    /** Arm the one-shot re-lock suppression for a permission grant round-trip. */
    fun beginGrantRoundTrip(nowMs: Long = System.currentTimeMillis()) {
        grantRoundTripArmedAtMs = nowMs
    }

    /**
     * Consume the one-shot suppression; true means "skip this re-lock". The flag is spent
     * either way, and an armed flag older than [GRANT_ROUND_TRIP_EXPIRY_MS] has already
     * expired and returns false, so a stale round-trip can never mask a real backgrounding.
     */
    fun consumeGrantRoundTrip(nowMs: Long = System.currentTimeMillis()): Boolean {
        val armedAtMs = grantRoundTripArmedAtMs
        grantRoundTripArmedAtMs = null
        return armedAtMs != null && nowMs - armedAtMs < GRANT_ROUND_TRIP_EXPIRY_MS
    }

    /**
     * True when [route] is behind the unlocked vault and so must be re-locked on background.
     * A null route (graph not yet resolved) is treated as pre-vault: nothing to lock.
     */
    fun isVaultSurface(route: String?): Boolean = route != null && route !in PRE_VAULT_SURFACES

    /**
     * True when a just-composed destination is the process-death signature and must be
     * forced back to the calculator lock (APP-240): the nav back stack survives process
     * death via saved instance state, but the in-memory [VaultSession] does not, so a cold
     * restore can resurrect a vault surface with no live session. Legitimate in-app
     * navigation can never look like this — every path onto a vault surface begins the
     * session first, and every leave-the-vault path clears it only while navigating to a
     * pre-vault surface — so vault surface + dead session always means "restored corpse".
     */
    fun requiresLockOnColdRestore(route: String?): Boolean = isVaultSurface(route) && VaultSession.passphrase == null

    /**
     * Forget the in-memory session and drop the data key + cached content so the vault is
     * unreachable without the PIN. Pure state drop — it never navigates — so it is safe
     * from ANY leave-the-vault path (APP-225 board finding P1a): the `ON_STOP`
     * backgrounding observer (gated on [isVaultSurface]) AND the vault-home `BackHandler`
     * that pops back out to the calculator disguise. Safe to call repeatedly, including
     * when nothing is unlocked.
     */
    fun relock() {
        VaultSession.clear()
        VaultGraph.contentRepository.lock()
    }

    /**
     * Alias for [relock] whose name states the caller's intent at back-navigation call
     * sites: the user is deliberately leaving the vault right now, not being backgrounded.
     */
    fun lockNow() = relock()
}
