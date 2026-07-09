package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Off-device proof of the recovery backoff persistence (PIN Recovery §1.6). The counters must:
 * (1) start empty, (2) count per-method so a spree on one path never eats the other's grace,
 * (3) **survive a fresh instance** (the file is the survive-uninstall source of truth), (4)
 * clear on a good unlock, and (5) feed [RecoveryBackoff] into a real lockout. A malformed or
 * missing file must read as "no failures" — defence in depth must never itself strand the user.
 */
class FileRecoveryBackoffStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(): File = File(tmp.newFolder(".CalcVault"), ".recovery_backoff")

    @Test
    fun `unseen method reads as no failures`() {
        val store = FileRecoveryBackoffStore(file())
        assertThat(store.read(RecoveryMethod.SECURITY_ANSWER)).isEqualTo(RecoveryAttempt.NONE)
    }

    @Test
    fun `failures count per method independently`() {
        val store = FileRecoveryBackoffStore(file())
        store.recordFailure(RecoveryMethod.SECURITY_ANSWER, 1_000L)
        store.recordFailure(RecoveryMethod.SECURITY_ANSWER, 2_000L)
        store.recordFailure(RecoveryMethod.RECOVERY_CODE, 5_000L)

        val answer = store.read(RecoveryMethod.SECURITY_ANSWER)
        assertThat(answer.failures).isEqualTo(2)
        assertThat(answer.lastFailureAtMillis).isEqualTo(2_000L)

        val code = store.read(RecoveryMethod.RECOVERY_CODE)
        assertThat(code.failures).isEqualTo(1)
        assertThat(code.lastFailureAtMillis).isEqualTo(5_000L)
    }

    @Test
    fun `counters survive a fresh instance over the same file`() {
        val f = file()
        FileRecoveryBackoffStore(f).apply {
            recordFailure(RecoveryMethod.RECOVERY_CODE, 7_000L)
            recordFailure(RecoveryMethod.RECOVERY_CODE, 8_000L)
            recordFailure(RecoveryMethod.RECOVERY_CODE, 9_000L)
        }
        // A brand-new store (simulating a relaunch / reinstall over the survive-uninstall file).
        val reread = FileRecoveryBackoffStore(f).read(RecoveryMethod.RECOVERY_CODE)
        assertThat(reread.failures).isEqualTo(3)
        assertThat(reread.lastFailureAtMillis).isEqualTo(9_000L)
    }

    @Test
    fun `clear forgets one method but leaves the other`() {
        val store = FileRecoveryBackoffStore(file())
        store.recordFailure(RecoveryMethod.SECURITY_ANSWER, 1_000L)
        store.recordFailure(RecoveryMethod.RECOVERY_CODE, 2_000L)

        store.clear(RecoveryMethod.SECURITY_ANSWER)

        assertThat(store.read(RecoveryMethod.SECURITY_ANSWER)).isEqualTo(RecoveryAttempt.NONE)
        assertThat(store.read(RecoveryMethod.RECOVERY_CODE).failures).isEqualTo(1)
    }

    @Test
    fun `a malformed file reads as no failures rather than crashing`() {
        val f = file()
        f.writeText("garbage\nB:notanumber:xyz\n")
        assertThat(FileRecoveryBackoffStore(f).read(RecoveryMethod.SECURITY_ANSWER)).isEqualTo(RecoveryAttempt.NONE)
    }

    @Test
    fun `three failures produce a real lockout via RecoveryBackoff`() {
        val store = FileRecoveryBackoffStore(file())
        repeat(3) { store.recordFailure(RecoveryMethod.SECURITY_ANSWER, 10_000L) }
        val a = store.read(RecoveryMethod.SECURITY_ANSWER)

        // Immediately after the 3rd failure the schedule imposes 30s.
        assertThat(
            RecoveryBackoff.remainingLockoutMillis(a.failures, a.lastFailureAtMillis, 10_000L)
        ).isEqualTo(30_000L)
        // Two failures alone are still inside the fat-finger grace.
        assertThat(RecoveryBackoff.isLockedOut(2, 10_000L, 10_000L)).isFalse()
    }
}
