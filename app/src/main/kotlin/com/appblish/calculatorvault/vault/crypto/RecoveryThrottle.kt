package com.appblish.calculatorvault.vault.crypto

/**
 * Brute-force backoff for every place a vault secret is typed — PIN, security answer, and
 * recovery code (spec §1.6, §3, §5.3, APP-322). A pure function of the *consecutive failure
 * count* so it is unit-testable and identical across the three entry points; the caller owns
 * persisting the count and the last-attempt timestamp and resets the count to 0 on success.
 *
 * Policy: the first [FREE_ATTEMPTS] wrong tries cost nothing (a fat-fingered PIN shouldn't
 * punish the real owner — and it is exactly the 3rd failure that also surfaces the "try
 * another way" recovery door, §3). After that each further failure locks input for an
 * exponentially growing window — [BASE_LOCKOUT_MILLIS] doubling per extra failure — capped at
 * [MAX_LOCKOUT_MILLIS] so a determined attacker is throttled to a handful of guesses per hour
 * while a legitimate user is never permanently bricked.
 */
object RecoveryThrottle {
    /** Wrong attempts allowed with no lockout before backoff kicks in. */
    const val FREE_ATTEMPTS = 3

    /** Lockout imposed at the first throttled failure; doubles per additional failure. */
    const val BASE_LOCKOUT_MILLIS = 5_000L

    /** Ceiling on the backoff so a legitimate user is throttled, never locked out forever. */
    const val MAX_LOCKOUT_MILLIS = 5 * 60_000L

    /**
     * The lockout window imposed *after* [consecutiveFailures] consecutive wrong attempts.
     * Returns 0 while within the free allowance, then `BASE * 2^(n - FREE - 1)` capped at
     * [MAX_LOCKOUT_MILLIS].
     */
    fun lockoutMillisAfter(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= FREE_ATTEMPTS) return 0
        val steps = consecutiveFailures - FREE_ATTEMPTS - 1
        // Shift on Long, guarding the cap before 2^steps can overflow.
        if (steps >= 63) return MAX_LOCKOUT_MILLIS
        val scaled = BASE_LOCKOUT_MILLIS shl steps
        return if (scaled in 0..MAX_LOCKOUT_MILLIS) scaled else MAX_LOCKOUT_MILLIS
    }

    /**
     * Remaining lockout in millis given the current [consecutiveFailures] and how long ago the
     * last attempt was ([millisSinceLastAttempt]). 0 means input is allowed again. UIs call
     * this to decide whether to accept a new secret or show a countdown.
     */
    fun remainingLockoutMillis(
        consecutiveFailures: Int,
        millisSinceLastAttempt: Long,
    ): Long {
        val window = lockoutMillisAfter(consecutiveFailures)
        val remaining = window - millisSinceLastAttempt.coerceAtLeast(0)
        return remaining.coerceAtLeast(0)
    }

    /** Convenience: is a new attempt currently allowed? */
    fun isAttemptAllowed(
        consecutiveFailures: Int,
        millisSinceLastAttempt: Long,
    ): Boolean = remainingLockoutMillis(consecutiveFailures, millisSinceLastAttempt) == 0L
}
