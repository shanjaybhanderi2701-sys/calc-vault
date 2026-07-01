package com.appblish.calculatorvault.applock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Exercises the re-lock timing state machine that drives enforcement. `now` is fed in so the
 * grace-window arithmetic is deterministic without a clock.
 */
class AppLockSessionTest {
    private val own = "com.appblish.calculatorvault"
    private val whatsapp = "com.whatsapp"
    private val instagram = "com.instagram.android"
    private val launcher = "com.android.launcher"

    @Test
    fun lockedApp_promptsWhenNotYetUnlocked() {
        val s = AppLockSession()
        assertThat(s.onForeground(whatsapp, isLocked = true, own, relockDelayMs = 0, now = 100)).isTrue()
    }

    @Test
    fun ownPackage_neverPrompts() {
        val s = AppLockSession()
        assertThat(s.onForeground(own, isLocked = true, own, relockDelayMs = 0, now = 100)).isFalse()
    }

    @Test
    fun unlockedApp_stayingForeground_doesNotRePrompt() {
        val s = AppLockSession()
        s.markUnlocked(whatsapp, now = 100)
        // The just-unlocked app refocuses (the event the OS fires after our activity finishes).
        assertThat(s.onForeground(whatsapp, isLocked = true, own, relockDelayMs = 0, now = 101)).isFalse()
    }

    @Test
    fun immediateRelock_promptsAgainAfterLeavingAndReturning() {
        val s = AppLockSession()
        s.markUnlocked(whatsapp, now = 100)
        assertThat(s.onForeground(whatsapp, isLocked = true, own, 0, 101)).isFalse()
        // Leave to the launcher (unlocked) → immediate relock closes the session.
        assertThat(s.onForeground(launcher, isLocked = false, own, 0, 200)).isFalse()
        // Return to WhatsApp → must challenge again.
        assertThat(s.onForeground(whatsapp, isLocked = true, own, 0, 300)).isTrue()
    }

    @Test
    fun gracedRelock_allowsReturnWithinWindowAndPromptsAfter() {
        val s = AppLockSession()
        val grace = 30_000L
        s.markUnlocked(whatsapp, now = 1_000)
        // Leave to launcher at t=2000 → starts a 30s grace window.
        assertThat(s.onForeground(launcher, isLocked = false, own, grace, 2_000)).isFalse()
        // Return at t=10_000 (within grace) → no prompt.
        assertThat(s.onForeground(whatsapp, isLocked = true, own, grace, 10_000)).isFalse()
        // Leave again, then return well after the window → prompt.
        assertThat(s.onForeground(launcher, isLocked = false, own, grace, 11_000)).isFalse()
        assertThat(s.onForeground(whatsapp, isLocked = true, own, grace, 100_000)).isTrue()
    }

    @Test
    fun switchingToAnotherLockedApp_promptsForIt() {
        val s = AppLockSession()
        s.markUnlocked(whatsapp, now = 100)
        // Directly switch to a different locked app → challenge the new one.
        assertThat(s.onForeground(instagram, isLocked = true, own, relockDelayMs = 60_000, now = 200)).isTrue()
    }

    @Test
    fun unlockedNonLockedApp_neverPrompts() {
        val s = AppLockSession()
        assertThat(s.onForeground(launcher, isLocked = false, own, 0, 100)).isFalse()
    }
}
