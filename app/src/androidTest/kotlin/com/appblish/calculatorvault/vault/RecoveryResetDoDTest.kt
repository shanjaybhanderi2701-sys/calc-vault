package com.appblish.calculatorvault.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.crypto.RecoveryEnvelope
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.crypto.RecoveryResetOutcome
import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * On-device DoD proof of the APP-325 W3 recovery reset seam ([RecoveryEnvelope.resetPin])
 * against the device's real Conscrypt provider. Proves, through both recovery paths:
 *  - (a) the PIN is reset via a Wrap-A-only re-wrap (the new PIN unwraps the same DEK, the old
 *        one no longer does),
 *  - (b) the reset does NOT break the OTHER recovery wrap — both the security answer and the
 *        recovery code still unwrap the DEK afterward,
 *  - (c) files are never re-encrypted — a blob hidden before the reset still decrypts.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryResetDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    private val pin = "1234"
    private val newPin = "5678"
    private val answer = "Mother's maiden name"
    private val code = "7K9F-2XQP-4MRT-8WVN"

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "app325_reset_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun keyFile() = File(dir, ".vaultkey")

    @Test
    fun answerPathResetsPinAndPreservesTheCodeWrapOnDevice() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)
        val secret = "APP-325 on-device :: reset via answer".toByteArray()
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dek).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        val outcome = RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.SECURITY_ANSWER, answer, newPin)

        assertThat(outcome).isEqualTo(RecoveryResetOutcome.RESET)
        assertThat(VaultKeyFile(file).unlock(newPin).encoded).isEqualTo(dek.encoded)
        assertThrowsWrongPassphrase { VaultKeyFile(file).unlock(pin) }
        // (b) the recovery code still unwraps the same DEK after the answer-driven reset.
        assertThat(VaultKeyFile(file).unlockWithRecoveryCode(code).encoded).isEqualTo(dek.encoded)
        // (c) the pre-reset blob still decrypts — no bulk re-encrypt.
        val out = ByteArrayOutputStream()
        VaultCrypto(VaultKeyFile(file).unlock(newPin)).decrypt(ByteArrayInputStream(blob), out)
        assertThat(out.toByteArray()).isEqualTo(secret)
    }

    @Test
    fun codePathResetsPinAndPreservesTheAnswerWrapOnDevice() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)

        val outcome = RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.RECOVERY_CODE, code, newPin)

        assertThat(outcome).isEqualTo(RecoveryResetOutcome.RESET)
        assertThat(VaultKeyFile(file).unlock(newPin).encoded).isEqualTo(dek.encoded)
        // (b) the security answer still unwraps the same DEK after the code-driven reset.
        assertThat(VaultKeyFile(file).unlockWithAnswer(answer).encoded).isEqualTo(dek.encoded)
        assertThat(VaultKeyFile(file).isRecoveryConfigured()).isTrue()
    }

    private fun assertThrowsWrongPassphrase(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected WrongPassphraseException")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct.
        }
    }
}
