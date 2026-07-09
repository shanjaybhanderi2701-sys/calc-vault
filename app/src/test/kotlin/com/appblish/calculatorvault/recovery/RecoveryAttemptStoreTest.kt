package com.appblish.calculatorvault.recovery

import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Off-device proof that the recovery backoff counters persist per method and survive a fresh
 * process (a new store instance over the same file) — the durability the spec §1.6 backoff
 * relies on so a reinstall/relaunch can't hand an attacker a fresh guessing budget.
 */
class RecoveryAttemptStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(): File = File(tmp.newFolder("vault"), ".recovery_attempts")

    @Test
    fun `records and reloads failures per method`() {
        val file = file()
        FileRecoveryAttemptStore(file).recordFailure(RecoveryMethod.RECOVERY_CODE, 111L)
        FileRecoveryAttemptStore(file).recordFailure(RecoveryMethod.RECOVERY_CODE, 222L)
        FileRecoveryAttemptStore(file).recordFailure(RecoveryMethod.SECURITY_ANSWER, 333L)

        // A brand-new instance (no in-memory state) reads the persisted counters.
        val reloaded = FileRecoveryAttemptStore(file)
        assertThat(reloaded.failedAttempts(RecoveryMethod.RECOVERY_CODE)).isEqualTo(2)
        assertThat(reloaded.lastFailureAtMillis(RecoveryMethod.RECOVERY_CODE)).isEqualTo(222L)
        assertThat(reloaded.failedAttempts(RecoveryMethod.SECURITY_ANSWER)).isEqualTo(1)
        assertThat(reloaded.lastFailureAtMillis(RecoveryMethod.SECURITY_ANSWER)).isEqualTo(333L)
    }

    @Test
    fun `clear resets one method without touching the other`() {
        val file = file()
        val store = FileRecoveryAttemptStore(file)
        store.recordFailure(RecoveryMethod.RECOVERY_CODE, 111L)
        store.recordFailure(RecoveryMethod.SECURITY_ANSWER, 222L)

        store.clear(RecoveryMethod.RECOVERY_CODE)

        val reloaded = FileRecoveryAttemptStore(file)
        assertThat(reloaded.failedAttempts(RecoveryMethod.RECOVERY_CODE)).isEqualTo(0)
        assertThat(reloaded.failedAttempts(RecoveryMethod.SECURITY_ANSWER)).isEqualTo(1)
    }

    @Test
    fun `unknown file reads as zero attempts`() {
        val store = FileRecoveryAttemptStore(File(tmp.newFolder("empty"), ".recovery_attempts"))
        assertThat(store.failedAttempts(RecoveryMethod.RECOVERY_CODE)).isEqualTo(0)
        assertThat(store.lastFailureAtMillis(RecoveryMethod.RECOVERY_CODE)).isEqualTo(0L)
    }

    @Test
    fun `a failed atomic swap throws instead of a non-atomic overwrite (APP-331 O2)`() {
        // Point the store at a path that is actually a non-empty directory: the temp file can be
        // written but `renameTo` onto it fails. The hardened write must fail closed (throw) rather
        // than fall back to a non-atomic `writeText` that could truncate the counter and silently
        // reset the survive-uninstall lockout to zero (spec §1.6).
        val dir = tmp.newFolder("vault")
        val target = File(dir, ".recovery_attempts")
        target.mkdir()
        File(target, "occupant").writeText("keep the dir non-empty")
        val store = FileRecoveryAttemptStore(target)

        var threw = false
        try {
            store.recordFailure(RecoveryMethod.RECOVERY_CODE, 111L)
        } catch (e: java.io.IOException) {
            threw = true
        }

        assertThat(threw).isTrue()
        // The target path was never clobbered into a truncated counter file — still the directory.
        assertThat(target.isDirectory).isTrue()
        // And no stray `.tmp` was left behind after the fail-closed throw.
        assertThat(File(dir, ".recovery_attempts.tmp").exists()).isFalse()
    }
}
