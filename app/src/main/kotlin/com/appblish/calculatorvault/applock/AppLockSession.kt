package com.appblish.calculatorvault.applock

/**
 * The live "which locked app is currently unlocked" session that drives re-lock timing.
 * Held in memory and shared process-wide between the [AppLockAccessibilityService] (which
 * asks, on every foreground change, whether to raise the lock screen) and the
 * [LockScreenActivity] (which marks a package unlocked on a correct PIN). Kept out of the
 * Android layer and fed `now` explicitly so the timing rules are unit-tested deterministically.
 *
 * Model: at most one locked app is "unlocked" at a time. While it stays in the foreground it
 * is allowed. When the user navigates away, `relockDelayMs` decides how long a return is
 * still allowed: `0` re-locks immediately; a positive delay opens a grace window measured
 * from the moment they left.
 */
class AppLockSession {
    private var unlockedPackage: String? = null

    // 0 while the unlocked app is in the foreground; when the user leaves, the absolute time
    // after which the grace window expires and the app re-locks.
    private var graceUntil: Long = ACTIVE

    /** Record a correct unlock for [packageName] at [now] — starts an active session. */
    fun markUnlocked(
        packageName: String,
        now: Long,
    ) {
        unlockedPackage = packageName
        graceUntil = ACTIVE
    }

    /** Forget any session (full reset / lock-all). */
    fun clear() {
        unlockedPackage = null
        graceUntil = ACTIVE
    }

    /** Test/observability hook: the package currently in an unlocked session, if any. */
    fun currentUnlocked(): String? = unlockedPackage

    /**
     * Fold a foreground change into the session and answer: *should the lock screen be shown
     * for [packageName]?* [isLocked] is whether the incoming package is in the locked set;
     * [ownPackage] is us (never challenged). Mutates internal session state.
     */
    fun onForeground(
        packageName: String,
        isLocked: Boolean,
        ownPackage: String,
        relockDelayMs: Long,
        now: Long,
    ): Boolean {
        // Our own UI (calculator, vault, the lock screen itself) is never challenged.
        if (packageName == ownPackage) return false

        // Case A — the incoming app is the one we unlocked.
        if (packageName == unlockedPackage) {
            if (graceUntil == ACTIVE) return false // still active in the app
            if (now < graceUntil) {
                graceUntil = ACTIVE // returned within the grace window → re-activate
                return false
            }
            // Grace expired while we were away → the app re-locks.
            unlockedPackage = null
            graceUntil = ACTIVE
            return isLocked
        }

        // Case B — we've switched to a different app. Close out the previous session.
        if (unlockedPackage != null) {
            if (graceUntil == ACTIVE) {
                // Just left the unlocked app: start its grace window, or expire immediately.
                if (relockDelayMs <= 0L) unlockedPackage = null else graceUntil = now + relockDelayMs
            } else if (now >= graceUntil) {
                unlockedPackage = null
                graceUntil = ACTIVE
            }
        }

        // The incoming app is a different one; challenge it iff it is locked.
        return isLocked
    }

    companion object {
        private const val ACTIVE = 0L

        /** Process-wide instance shared by the service and the lock activity. */
        val shared = AppLockSession()
    }
}
