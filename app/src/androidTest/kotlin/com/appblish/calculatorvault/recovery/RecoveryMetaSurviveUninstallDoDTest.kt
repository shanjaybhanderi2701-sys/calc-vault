package com.appblish.calculatorvault.recovery

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device DoD proof of the APP-338 fix: the PIN-recovery **security-question prompt** and the
 * **recovery-configured status** now live in the survive-uninstall `.CalcVault/` folder beside
 * the wraps, so an uninstall+reinstall (which wipes app-private storage + the Android Keystore)
 * no longer loses the prompt or mis-reports the status.
 *
 * "Reinstall" is simulated exactly as [com.appblish.calculatorvault.vault.PublicStorageSurviveUninstallTest]
 * does — a **fresh** [VaultKeyFileRecoveryManager] over the same shared-storage folder, with the
 * app-private legacy prefs deleted first — which is precisely the state a real reinstall leaves:
 * empty app-private state + no keystore material, pointed at the surviving public folder.
 *
 * Proves:
 *  1. Set up recovery → reinstall → status is "configured", the **question prompt reads back**,
 *     and BOTH the security-answer and recovery-code wraps still unwrap the same DEK.
 *  2. Skip recovery → reinstall → status is "not configured" (so the grid warning banner shows).
 *  3. Completing setup flips the status from not-configured to configured, and the metadata write
 *     is visible immediately after it commits.
 *  4. The metadata written to the public folder is the plaintext prompt only — no secret.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryMetaSurviveUninstallDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val pin = "1234"
    private val question = "What was your first pet's name?"
    private val answer = "  Rex the Dog  " // raw; the wrap normalizes internally
    private val code = "7K9F-2XQP-4MRT-8WVN"

    @Before
    fun grantAllFilesAccessAndClean() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
        )
        repeat(40) { if (StoragePermissions.hasAllFilesAccess(context)) return@repeat else Thread.sleep(50) }
        assertThat(StoragePermissions.hasAllFilesAccess(context)).isTrue()
        File(Environment.getExternalStorageDirectory(), VaultStorage.DIR_NAME).deleteRecursively()
        wipeAppPrivateRecoveryPrefs()
        // Every scenario targets the real vault (empty namespace).
        VaultSession.begin(pin)
    }

    @After
    fun cleanUp() {
        File(Environment.getExternalStorageDirectory(), VaultStorage.DIR_NAME).deleteRecursively()
        wipeAppPrivateRecoveryPrefs()
    }

    @Test
    fun setUpUserSurvivesReinstall() =
        runBlocking {
            val dek = VaultKeyFile(VaultStorage.keyFile(context)).unlockOrCreate(pin)
            VaultKeyFileRecoveryManager(context).setUp(question, answer, code)

            // The prompt landed in the survive-uninstall public folder, not app-private storage.
            assertThat(VaultStorage.recoveryMetaFile(context).absolutePath).contains("/.CalcVault")
            assertThat(VaultStorage.recoveryMetaFile(context).exists()).isTrue()

            // --- "Reinstall": app-private + keystore gone, public folder kept ---------------
            wipeAppPrivateRecoveryPrefs()
            val reinstalled = VaultKeyFileRecoveryManager(context)

            assertThat(reinstalled.isConfigured()).isTrue()
            assertThat(reinstalled.configuredQuestion()).isEqualTo(question)

            // BOTH recovery wraps still unwrap the very same DEK after the "reinstall".
            assertThat(VaultKeyFile(VaultStorage.keyFile(context)).unlockWithAnswer(answer).encoded)
                .isEqualTo(dek.encoded)
            assertThat(VaultKeyFile(VaultStorage.keyFile(context)).unlockWithRecoveryCode(code).encoded)
                .isEqualTo(dek.encoded)
        }

    @Test
    fun skippedUserIsUnconfiguredAfterReinstall() =
        runBlocking {
            // Vault exists (Wrap A only) but recovery was skipped — nothing written to B/C.
            VaultKeyFile(VaultStorage.keyFile(context)).unlockOrCreate(pin)

            wipeAppPrivateRecoveryPrefs()
            val reinstalled = VaultKeyFileRecoveryManager(context)

            // configured=false → the grid warning banner is eligible to show again.
            assertThat(reinstalled.isConfigured()).isFalse()
            assertThat(reinstalled.configuredQuestion()).isNull()
        }

    @Test
    fun completingSetupFlipsStatusToConfigured() =
        runBlocking {
            VaultKeyFile(VaultStorage.keyFile(context)).unlockOrCreate(pin)
            val manager = VaultKeyFileRecoveryManager(context)

            assertThat(manager.isConfigured()).isFalse()

            manager.setUp(question, answer, code)

            // Visible immediately after the keyfile + metadata writes commit.
            assertThat(manager.isConfigured()).isTrue()
            assertThat(manager.configuredQuestion()).isEqualTo(question)
        }

    @Test
    fun metadataOnDiskCarriesNoSecret() =
        runBlocking {
            VaultKeyFile(VaultStorage.keyFile(context)).unlockOrCreate(pin)
            VaultKeyFileRecoveryManager(context).setUp(question, answer, code)

            val onDisk = VaultStorage.recoveryMetaFile(context).readText()
            // Only the magic + the plaintext prompt — never the answer, the code, or key material.
            assertThat(onDisk).isEqualTo("CVRMETA1\n$question")
            assertThat(onDisk).doesNotContain("Rex")
            assertThat(onDisk).doesNotContain(code)
        }

    /** Delete the legacy app-private EncryptedSharedPreferences that once held the prompt. */
    private fun wipeAppPrivateRecoveryPrefs() {
        context.deleteSharedPreferences(LEGACY_PREFS_NAME)
    }

    private companion object {
        const val LEGACY_PREFS_NAME = "calcvault_recovery_meta"
    }
}
