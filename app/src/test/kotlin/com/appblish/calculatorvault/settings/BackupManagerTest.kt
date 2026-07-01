package com.appblish.calculatorvault.settings

import com.appblish.calculatorvault.auth.InMemoryCredentialStore
import com.appblish.calculatorvault.auth.RecoverySetup
import com.appblish.calculatorvault.auth.SecurityQuestion
import com.appblish.calculatorvault.auth.VaultKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Proves the Phase-5 acceptance line "backup verified restorable": a backup created from a
 * fully-configured vault restores the exact PIN, decoy, recovery, and settings into a wiped
 * pair of stores, and is unreadable without the right password.
 */
class BackupManagerTest {
    private fun fixtures(): Triple<InMemoryCredentialStore, InMemorySettingsStore, BackupManager> {
        val cred = InMemoryCredentialStore()
        val settings = InMemorySettingsStore()
        return Triple(cred, settings, BackupManager(cred, settings))
    }

    @Test
    fun `backup restores the real pin, decoy, recovery and settings after a wipe`() =
        runTest {
            val (cred, settings, manager) = fixtures()
            cred.setRealPin("1234")
            val slot = cred.addDecoyPin("5555")!!
            cred.setRecovery(
                RecoverySetup(SecurityQuestion.PET_NAME, "Rex", "owner@example.com", "the dog"),
            )
            cred.completeOnboarding()
            settings.save(
                VaultSettings(
                    keypadSkin = KeypadSkin.INDIGO_NIGHT,
                    unlockAnimation = UnlockAnimation.NONE,
                    preventUninstallEnabled = true,
                ),
            )

            val blob = manager.createBackup("hunter2")

            // Wipe everything, as if restoring onto a fresh install.
            cred.clearAll()
            settings.importRaw(emptyMap())
            assertThat(cred.resolve("1234")).isNull()

            manager.restoreBackup(blob, "hunter2")

            assertThat(cred.resolve("1234")).isEqualTo(VaultKind.Real)
            assertThat(cred.resolve("5555")).isEqualTo(VaultKind.Decoy(slot))
            assertThat(cred.isOnboarded()).isTrue()
            assertThat(cred.verifyRecoveryAnswer("rex")).isTrue()
            val restored = settings.load()
            assertThat(restored.keypadSkin).isEqualTo(KeypadSkin.INDIGO_NIGHT)
            assertThat(restored.unlockAnimation).isEqualTo(UnlockAnimation.NONE)
            assertThat(restored.preventUninstallEnabled).isTrue()
        }

    @Test
    fun `a wrong password never restores anything`() =
        runTest {
            val (cred, _, manager) = fixtures()
            cred.setRealPin("1234")
            val blob = manager.createBackup("correct-horse")

            cred.clearAll()
            try {
                manager.restoreBackup(blob, "wrong-password")
                assert(false) { "expected BackupException" }
            } catch (e: BackupException) {
                // expected
            }
            assertThat(cred.resolve("1234")).isNull()
        }

    @Test
    fun `a tampered blob is rejected`() =
        runTest {
            val (cred, _, manager) = fixtures()
            cred.setRealPin("1234")
            val blob = manager.createBackup("password")
            val tampered = blob.dropLast(2) + "ff"
            try {
                manager.restoreBackup(tampered, "password")
                assert(false) { "expected BackupException" }
            } catch (e: BackupException) {
                // expected
            }
        }

    @Test
    fun `too-short backup passwords are refused`() =
        runTest {
            val (_, _, manager) = fixtures()
            try {
                manager.createBackup("123")
                assert(false) { "expected IllegalArgumentException" }
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }

    @Test
    fun `the same payload encrypts differently each time`() =
        runTest {
            val (cred, _, manager) = fixtures()
            cred.setRealPin("1234")
            val a = manager.createBackup("password")
            val b = manager.createBackup("password")
            // Fresh random salt + IV per backup ⇒ ciphertext differs, yet both restore.
            assertThat(a).isNotEqualTo(b)
        }
}
