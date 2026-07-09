package com.appblish.calculatorvault.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * On-device DoD proof of the 3-wrap recovery envelope (PIN Recovery spec §1, APP-322). The unit
 * suite ([com.appblish.calculatorvault.vault.crypto.ThreeWrapEnvelopeTest]) already exercises the
 * model; this instrumented copy re-runs the crux against the **device's real Conscrypt provider**
 * — AES/GCM key-wrapping and PBKDF2-on-HMAC must behave identically there for the survive-uninstall
 * guarantee to hold on shipping hardware. Runs on the CI instrumented matrix, no manual emulator.
 */
@RunWith(AndroidJUnit4::class)
class ThreeWrapEnvelopeDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    private val pin = "1234"
    private val answer = "Mother's maiden name"
    private val code = "7K9F-2XQP-4MRT-8WVN"

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "app322_envelope_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun keyFile() = File(dir, ".vaultkey")

    @Test
    fun eachOfTheThreeWrapsIndependentlyUnwrapsTheSameDekOnDevice() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)

        // Encrypt a blob under the DEK, then recover it through every path on a fresh instance.
        val secret = "APP-322 on-device :: one DEK, three doors".toByteArray()
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dek).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        val paths =
            listOf(
                VaultKeyFile(file).unlock(pin),
                VaultKeyFile(file).unlockWithAnswer(answer),
                VaultKeyFile(file).unlockWithRecoveryCode(code),
            )
        for (recovered in paths) {
            assertThat(recovered.encoded).isEqualTo(dek.encoded)
            val out = ByteArrayOutputStream()
            VaultCrypto(recovered).decrypt(ByteArrayInputStream(blob), out)
            assertThat(out.toByteArray()).isEqualTo(secret)
        }
    }

    @Test
    fun recoveryResetRewrapsPinWithoutTouchingBlobsOnDevice() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)
        val secret = "hidden before the reset".toByteArray()
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dek).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        // Forgot PIN → unwrap via the recovery code → set a new PIN (re-create Wrap A only).
        val recoveredDek = VaultKeyFile(file).unlockWithRecoveryCode(code)
        VaultKeyFile(file).replacePinWrap(recoveredDek, "5678")

        assertThat(VaultKeyFile(file).unlock("5678").encoded).isEqualTo(dek.encoded)
        // Old PIN is dead; the blob — never re-encrypted — still decrypts.
        try {
            VaultKeyFile(file).unlock(pin)
            throw AssertionError("Old PIN must not unlock after reset")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct.
        }
        val out = ByteArrayOutputStream()
        VaultCrypto(VaultKeyFile(file).unlock("5678")).decrypt(ByteArrayInputStream(blob), out)
        assertThat(out.toByteArray()).isEqualTo(secret)
    }

    @Test
    fun noPlaintextSecretIsWrittenToTheKeyFileOnDevice() {
        val file = keyFile()
        VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)

        val onDisk = file.readText()
        assertThat(onDisk.lowercase()).doesNotContain("maiden")
        assertThat(onDisk.uppercase()).doesNotContain("7K9F2XQP4MRT8WVN")

        // The all-numeric PIN would false-match ~1/65k of the time as a random hex substring, so
        // assert against the DECODED salt/iv/ciphertext bytes instead (non-flaky, and the true
        // "no recoverable plaintext" guarantee): the PIN's raw bytes must appear in no payload.
        val hex =
            onDisk
                .trim()
                .lines()
                .drop(1)
                .flatMap { it.split(":").drop(2) }
                .joinToString("")
        val payload =
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        assertThat(String(payload, Charsets.ISO_8859_1)).doesNotContain(pin)
    }
}
