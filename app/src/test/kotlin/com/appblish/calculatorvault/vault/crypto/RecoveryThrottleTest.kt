package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Off-device proof of the brute-force backoff policy (APP-322 §1.6). The first few misses are
 * free (a fat-fingered owner isn't punished), then lockout grows exponentially and is capped
 * so an attacker is throttled while a legitimate user is never bricked forever.
 */
class RecoveryThrottleTest {
    @Test
    fun `first free attempts impose no lockout`() {
        for (n in 0..RecoveryThrottle.FREE_ATTEMPTS) {
            assertThat(RecoveryThrottle.lockoutMillisAfter(n)).isEqualTo(0)
        }
    }

    @Test
    fun `lockout starts at the base window on the first throttled failure`() {
        assertThat(RecoveryThrottle.lockoutMillisAfter(RecoveryThrottle.FREE_ATTEMPTS + 1))
            .isEqualTo(RecoveryThrottle.BASE_LOCKOUT_MILLIS)
    }

    @Test
    fun `lockout doubles per additional failure`() {
        val first = RecoveryThrottle.lockoutMillisAfter(RecoveryThrottle.FREE_ATTEMPTS + 1)
        val second = RecoveryThrottle.lockoutMillisAfter(RecoveryThrottle.FREE_ATTEMPTS + 2)
        val third = RecoveryThrottle.lockoutMillisAfter(RecoveryThrottle.FREE_ATTEMPTS + 3)
        assertThat(second).isEqualTo(first * 2)
        assertThat(third).isEqualTo(first * 4)
    }

    @Test
    fun `lockout is capped and never overflows for huge failure counts`() {
        assertThat(RecoveryThrottle.lockoutMillisAfter(1_000)).isEqualTo(RecoveryThrottle.MAX_LOCKOUT_MILLIS)
        assertThat(RecoveryThrottle.lockoutMillisAfter(Int.MAX_VALUE)).isEqualTo(RecoveryThrottle.MAX_LOCKOUT_MILLIS)
    }

    @Test
    fun `remaining lockout counts down and a new attempt is allowed once elapsed`() {
        val failures = RecoveryThrottle.FREE_ATTEMPTS + 1
        val window = RecoveryThrottle.lockoutMillisAfter(failures)

        assertThat(RecoveryThrottle.remainingLockoutMillis(failures, 0)).isEqualTo(window)
        assertThat(RecoveryThrottle.remainingLockoutMillis(failures, window / 2)).isEqualTo(window - window / 2)
        assertThat(RecoveryThrottle.isAttemptAllowed(failures, window)).isTrue()
        assertThat(RecoveryThrottle.isAttemptAllowed(failures, 0)).isFalse()
    }
}
