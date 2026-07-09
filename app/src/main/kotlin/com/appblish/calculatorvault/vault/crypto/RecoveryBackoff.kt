package com.appblish.calculatorvault.vault.crypto

/**
 * Pure brute-force **backoff policy** for every place a vault secret is entered — PIN, security
 * answer, recovery code (PIN Recovery spec §1.6). Given the number of consecutive failures and
 * the wall-clock time of the last one, it reports how long entry must stay locked out. It holds
 * no state and touches no clock or storage: callers own persisting `failedAttempts` /
 * `lastFailureAtMillis` (survive-uninstall, per secret) and pass `nowMillis` in, so the whole
 * policy is deterministic and unit-testable off-device.
 *
 * The DEK itself is already brute-force-hard (PBKDF2, 120k iterations per guess — see
 * [VaultKeyFile]); this backoff is defence in depth at the UI, turning an online guessing spree
 * into an escalating wait rather than an unbounded hammer.
 *
 * Schedule (consecutive failures → lockout after that failure):
 * ```
 *   1–2 : none        (fat-finger grace)
 *     3 : 30s
 *     4 : 60s
 *     5 : 5m
 *   6–9 : 15m
 *   10+ : 60m  (cap)
 * ```
 */
object RecoveryBackoff {
    /** How long, in ms, entry stays locked after [failedAttempts] consecutive failures. */
    fun lockoutMillisAfter(failedAttempts: Int): Long =
        when {
            failedAttempts <= FREE_ATTEMPTS -> 0L
            failedAttempts == 3 -> 30_000L
            failedAttempts == 4 -> 60_000L
            failedAttempts == 5 -> 5 * 60_000L
            failedAttempts <= 9 -> 15 * 60_000L
            else -> 60 * 60_000L
        }

    /**
     * Milliseconds still to wait before entry is allowed again, `0` if not locked out. A caller
     * with `failedAttempts` failures whose last failure was at [lastFailureAtMillis] is unlocked
     * once [nowMillis] passes `lastFailureAtMillis + lockoutMillisAfter(failedAttempts)`. Guards
     * against a backwards clock jump (returns the full remaining window rather than a negative).
     */
    fun remainingLockoutMillis(
        failedAttempts: Int,
        lastFailureAtMillis: Long,
        nowMillis: Long,
    ): Long {
        val lockout = lockoutMillisAfter(failedAttempts)
        if (lockout == 0L) return 0L
        val elapsed = nowMillis - lastFailureAtMillis
        if (elapsed < 0L) return lockout // clock moved backwards — stay locked the full window
        return (lockout - elapsed).coerceAtLeast(0L)
    }

    /** True if entry is currently locked out for the given failure count / timing. */
    fun isLockedOut(
        failedAttempts: Int,
        lastFailureAtMillis: Long,
        nowMillis: Long,
    ): Boolean = remainingLockoutMillis(failedAttempts, lastFailureAtMillis, nowMillis) > 0L

    /** Consecutive failures below this incur no lockout (typo grace). */
    private const val FREE_ATTEMPTS = 2
}
