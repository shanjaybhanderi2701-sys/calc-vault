package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The brute-force backoff schedule for secret entry (PIN Recovery spec §1.6). */
class RecoveryBackoffTest {
    @Test
    fun `first two attempts are free then lockout escalates and caps`() {
        assertThat(RecoveryBackoff.lockoutMillisAfter(1)).isEqualTo(0L)
        assertThat(RecoveryBackoff.lockoutMillisAfter(2)).isEqualTo(0L)
        assertThat(RecoveryBackoff.lockoutMillisAfter(3)).isEqualTo(30_000L)
        assertThat(RecoveryBackoff.lockoutMillisAfter(4)).isEqualTo(60_000L)
        assertThat(RecoveryBackoff.lockoutMillisAfter(5)).isEqualTo(5 * 60_000L)
        assertThat(RecoveryBackoff.lockoutMillisAfter(9)).isEqualTo(15 * 60_000L)
        // Caps and never grows unbounded.
        assertThat(RecoveryBackoff.lockoutMillisAfter(10)).isEqualTo(60 * 60_000L)
        assertThat(RecoveryBackoff.lockoutMillisAfter(1000)).isEqualTo(60 * 60_000L)
    }

    @Test
    fun `remaining lockout counts down from the last failure`() {
        val lastFail = 1_000_000L
        // 3 failures → 30s lockout.
        assertThat(RecoveryBackoff.remainingLockoutMillis(3, lastFail, lastFail)).isEqualTo(30_000L)
        assertThat(RecoveryBackoff.remainingLockoutMillis(3, lastFail, lastFail + 10_000L)).isEqualTo(20_000L)
        assertThat(RecoveryBackoff.remainingLockoutMillis(3, lastFail, lastFail + 30_000L)).isEqualTo(0L)
        assertThat(RecoveryBackoff.remainingLockoutMillis(3, lastFail, lastFail + 999_999L)).isEqualTo(0L)
    }

    @Test
    fun `no lockout while within the free attempts`() {
        assertThat(RecoveryBackoff.isLockedOut(2, 1_000L, 1_000L)).isFalse()
        assertThat(RecoveryBackoff.remainingLockoutMillis(2, 1_000L, 1_000L)).isEqualTo(0L)
    }

    @Test
    fun `a backwards clock jump keeps entry locked the full window`() {
        // now earlier than the recorded last failure (clock moved back) → stay locked.
        assertThat(RecoveryBackoff.remainingLockoutMillis(4, 5_000L, 1_000L)).isEqualTo(60_000L)
        assertThat(RecoveryBackoff.isLockedOut(4, 5_000L, 1_000L)).isTrue()
    }
}
