package com.appblish.calculatorvault.vault

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.crypto.RecoveryResetOutcome
import com.appblish.calculatorvault.vault.crypto.RecoveryVerifyOutcome
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.appblish.calculatorvault.vault.crypto.VaultKeyFileRecoveryPinReset
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 data-safety DoD proof for the forgot-PIN recovery reset (PIN Recovery W3, APP-325).
 * Hide an item under the old PIN, set up recovery, then reset the PIN through the real device
 * seam ([VaultKeyFileRecoveryPinReset]) — via BOTH recovery paths — and prove the vault did
 * NOT strand:
 *
 *  (a) each recovery path (Wrap B answer, Wrap C code) unwraps the DEK and the new PIN then
 *      lists **and decrypts** the item;
 *  (b) the reset re-wraps **Wrap A only** — Wrap B and Wrap C still unwrap the same DEK after;
 *  (c) files are never re-encrypted — the hidden original stays gone from MediaStore throughout
 *      (the in-process "adb content query" assertion) and the same ciphertext decrypts;
 *  (d) wrong answers escalate through the survive-uninstall backoff into a real lockout.
 *
 * Runs on the CI instrumented matrix — no manual emulator screenshots.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryPinResetDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_recovery"
    private val displayName = "calcvault_dod_recovery_${System.nanoTime()}.jpg"
    private val relativePath = "DCIM/CalcVaultDoD/"
    private val oldPin = "1111"
    private val answer = "Fluffy The Cat"
    private val code = "7K9F-2XQP-4MRT-8WVN"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
    }

    @After
    fun cleanUp() {
        DoDTestSupport.deleteImageRows(context, displayName)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    private fun keyFile() = VaultKeyFile(VaultStorage.keyFile(context, namespace))

    /** Hide one image under [oldPin] and configure recovery (Wrap B + Wrap C). Returns (storedId, plaintext). */
    private fun hideAndConfigureRecovery(): Pair<String, ByteArray> =
        runBlocking {
            VaultSession.begin(oldPin, namespace = namespace)
            val original = DoDTestSupport.sampleJpegBytes()
            val sourceUri = DoDTestSupport.insertPublicImage(context, displayName, relativePath, original)

            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)
            val staged =
                VaultItem(
                    id = "staged",
                    category = VaultCategory.PHOTOS,
                    originalName = displayName,
                    dateLabel = "Today",
                    sortKey = System.currentTimeMillis(),
                    sourceUri = sourceUri.toString(),
                    mimeType = "image/jpeg",
                    relativePath = relativePath,
                )
            val stored = repo.hide(listOf(staged)).single()
            context.contentResolver.delete(sourceUri, null, null)
            assertThat(DoDTestSupport.imageRowCount(context, displayName)).isEqualTo(0)

            // Configure recovery on the same envelope (Wrap B + Wrap C for the same DEK).
            keyFile().setUpRecovery(oldPin, answer, code)
            assertThat(keyFile().isRecoveryConfigured()).isTrue()
            stored.id to original
        }

    /** Reset via [method]/[secret] to [newPin] through the real seam, asserting the outcomes. */
    private fun resetVia(
        method: RecoveryMethod,
        secret: String,
        newPin: String,
    ) = runBlocking {
        val seam = VaultKeyFileRecoveryPinReset(context, namespace = namespace)
        assertThat(seam.verify(method, secret)).isEqualTo(RecoveryVerifyOutcome.Verified)
        assertThat(seam.resetPin(newPin)).isEqualTo(RecoveryResetOutcome.RESET)
    }

    /** After a reset, prove the item survives under [newPin], recovery wraps still work, old PIN is dead. */
    private fun assertVaultSurvives(
        storedId: String,
        original: ByteArray,
        newPin: String,
    ) = runBlocking {
        // (a) The new PIN opens the same vault: item lists and decrypts.
        VaultSession.begin(newPin, namespace = namespace)
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        val listed = repo.items(VaultCategory.PHOTOS).first()
        assertThat(listed.map { it.id }).contains(storedId)
        assertThat(repo.openDecrypted(storedId)).isEqualTo(original)
        // (c) The original never reappeared in MediaStore.
        assertThat(DoDTestSupport.imageRowCount(context, displayName)).isEqualTo(0)

        // (b) Wrap B and Wrap C still unwrap the SAME DEK — the reset touched Wrap A only.
        val dekViaNewPin = keyFile().unlock(newPin).encoded
        assertThat(keyFile().unlockWithAnswer(answer).encoded).isEqualTo(dekViaNewPin)
        assertThat(keyFile().unlockWithRecoveryCode(code).encoded).isEqualTo(dekViaNewPin)

        // The forgotten PIN no longer unwraps anything.
        try {
            keyFile().unlock(oldPin)
            throw AssertionError("The forgotten PIN must not unwrap after a recovery reset")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct.
        }
    }

    @Test
    fun securityAnswerPathResetsPinWrapAOnlyAndItemSurvives() {
        val (storedId, original) = hideAndConfigureRecovery()
        val newPin = "2222"
        resetVia(RecoveryMethod.SECURITY_ANSWER, answer, newPin)
        assertVaultSurvives(storedId, original, newPin)
    }

    @Test
    fun recoveryCodePathResetsPinWrapAOnlyAndItemSurvives() {
        val (storedId, original) = hideAndConfigureRecovery()
        val newPin = "3333"
        // A dashless / lower-case re-entry of the code must still unwrap (normalization).
        resetVia(RecoveryMethod.RECOVERY_CODE, code.replace("-", "").lowercase(), newPin)
        assertVaultSurvives(storedId, original, newPin)
    }

    @Test
    fun wrongAnswersEscalateIntoARealLockout() =
        runBlocking {
            hideAndConfigureRecovery()
            val seam = VaultKeyFileRecoveryPinReset(context, namespace = namespace)

            // First two wrong tries are inside the fat-finger grace (no lockout imposed yet).
            repeat(2) {
                val outcome = seam.verify(RecoveryMethod.SECURITY_ANSWER, "wrong answer")
                assertThat(outcome).isInstanceOf(RecoveryVerifyOutcome.WrongSecret::class.java)
                assertThat((outcome as RecoveryVerifyOutcome.WrongSecret).remainingLockoutMillis).isEqualTo(0L)
            }
            // The third wrong try trips the schedule → a positive lockout.
            val third = seam.verify(RecoveryMethod.SECURITY_ANSWER, "wrong answer")
            assertThat((third as RecoveryVerifyOutcome.WrongSecret).remainingLockoutMillis).isGreaterThan(0L)

            // Now even a correct answer is refused until the wait elapses.
            val locked = seam.verify(RecoveryMethod.SECURITY_ANSWER, answer)
            assertThat(locked).isInstanceOf(RecoveryVerifyOutcome.LockedOut::class.java)

            // The counter is survive-uninstall: a fresh seam still sees the lockout.
            val freshSeam = VaultKeyFileRecoveryPinReset(context, namespace = namespace)
            assertThat(freshSeam.lockoutRemainingMillis(RecoveryMethod.SECURITY_ANSWER)).isGreaterThan(0L)
            // And the OTHER method kept its full grace — per-secret counters.
            assertThat(freshSeam.lockoutRemainingMillis(RecoveryMethod.RECOVERY_CODE)).isEqualTo(0L)
        }
}
