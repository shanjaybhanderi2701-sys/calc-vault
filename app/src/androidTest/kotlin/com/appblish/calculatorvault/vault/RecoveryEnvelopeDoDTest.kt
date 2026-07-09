package com.appblish.calculatorvault.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.crypto.RecoveryEnvelope
import com.appblish.calculatorvault.vault.crypto.SecretKeyWrap
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
 * On-device DoD for the 3-wrap envelope (APP-322 §1, §7). The unit tests prove the model on
 * the JVM; this proves the **device's real security provider** (Conscrypt PBKDF2/HMAC + AES-GCM
 * hardware paths) produces the identical result — each of the PIN, security answer, and
 * recovery code independently unwraps the same DEK, and a recovery reset re-wraps Wrap A only
 * while leaving a pre-recovery blob readable. Runs hermetically in the app's cache dir, so it
 * needs no storage permission and never touches a real vault.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryEnvelopeDoDTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "recovery-dod-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun keyFile() = File(dir, ".vaultkey")

    private fun recoveryFile() = File(dir, ".vaultrecovery")

    @Test
    fun allThreeWrapsUnwrapTheSameDekOnDevice() {
        val dek = VaultKeyFile(keyFile()).unlockOrCreate("1234")
        val envelope = RecoveryEnvelope(recoveryFile())
        envelope.setSecurityAnswer(dek, "Fluffy")
        envelope.setRecoveryCode(dek, "7K9F-2XQP-4MRT-8WVN")

        val viaPin = VaultKeyFile(keyFile()).unlock("1234").encoded
        val viaAnswer = RecoveryEnvelope(recoveryFile()).unlockWithSecurityAnswer("fluffy").encoded
        val viaCode = RecoveryEnvelope(recoveryFile()).unlockWithRecoveryCode("7k9f2xqp4mrt8wvn").encoded

        assertThat(viaPin).isEqualTo(dek.encoded)
        assertThat(viaAnswer).isEqualTo(dek.encoded)
        assertThat(viaCode).isEqualTo(dek.encoded)
    }

    @Test
    fun recoveryResetReWrapsPinOnlyAndKeepsBlobsReadableOnDevice() {
        val secret = "APP-322 device blob".toByteArray()
        val dek = VaultKeyFile(keyFile()).unlockOrCreate("1111")
        RecoveryEnvelope(recoveryFile()).setRecoveryCode(dek, "7K9F-2XQP-4MRT-8WVN")
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dek).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        val recoveredDek = RecoveryEnvelope(recoveryFile()).unlockWithRecoveryCode("7K9F-2XQP-4MRT-8WVN")
        VaultKeyFile(keyFile()).writePinWrap(recoveredDek, "9999")

        val viaNewPin = VaultKeyFile(keyFile()).unlock("9999")
        assertThat(viaNewPin.encoded).isEqualTo(dek.encoded)
        val recovered =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(viaNewPin).decrypt(ByteArrayInputStream(blob), out) }
                .toByteArray()
        assertThat(recovered).isEqualTo(secret)
    }

    @Test
    fun wrongSecretFailsOnDevice() {
        val dek = VaultKeyFile(keyFile()).unlockOrCreate("1234")
        RecoveryEnvelope(recoveryFile()).setSecurityAnswer(dek, "Fluffy")

        try {
            RecoveryEnvelope(recoveryFile()).unlockWithSecurityAnswer("wrong")
            throw AssertionError("Expected WrongSecretException on device")
        } catch (expected: SecretKeyWrap.WrongSecretException) {
            // Correct: the device provider authenticates GCM exactly like the JVM.
        }
    }
}
