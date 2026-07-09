package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Off-device proof of the **3-wrap envelope** (APP-322 §1) — the crypto foundation the whole
 * PIN-recovery feature stands on, and the boundary the Security Engineer signs off. These
 * tests demonstrate, without a device, that:
 *
 * - the PIN, the security answer, and the recovery code each **independently unwrap the same
 *   DEK** (envelope encryption, §1.2);
 * - no plaintext secret and no raw DEK ever land on disk (§1.1);
 * - recovery is `unwrap + re-wrap` — a PIN reset re-creates Wrap A only and never touches the
 *   files, and blobs sealed before recovery still open afterwards (§1.3);
 * - a PIN change does not invalidate the recovery wraps (§7 DoD);
 * - a wrong secret fails, and an unconfigured path is distinguishable from a wrong answer;
 * - regenerating a recovery code retires the old one and leaves the security answer intact.
 */
class RecoveryEnvelopeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder(".CalcVault")

    private fun keyFile(dir: File) = File(dir, ".vaultkey")

    private fun recoveryFile(dir: File) = File(dir, ".vaultrecovery")

    /** Set up a vault (PIN wrap) plus both recovery wraps over the one DEK, as W2 setup will. */
    private fun setUpAllThree(
        dir: File,
        pin: String,
        answer: String,
        code: String,
    ): ByteArray {
        val dek = VaultKeyFile(keyFile(dir)).unlockOrCreate(pin)
        val envelope = RecoveryEnvelope(recoveryFile(dir))
        envelope.setSecurityAnswer(dek, answer)
        envelope.setRecoveryCode(dek, code)
        return dek.encoded
    }

    @Test
    fun `all three secrets independently unwrap the same DEK`() {
        val dir = dir()
        val expected = setUpAllThree(dir, pin = "1234", answer = "Fluffy", code = "7K9F-2XQP-4MRT-8WVN")

        // Fresh instances (no in-memory state) — as if the app were just reinstalled.
        val viaPin = VaultKeyFile(keyFile(dir)).unlock("1234").encoded
        val viaAnswer = RecoveryEnvelope(recoveryFile(dir)).unlockWithSecurityAnswer("fluffy").encoded
        val viaCode = RecoveryEnvelope(recoveryFile(dir)).unlockWithRecoveryCode("7k9f2xqp4mrt8wvn").encoded

        assertThat(viaPin).isEqualTo(expected)
        assertThat(viaAnswer).isEqualTo(expected)
        assertThat(viaCode).isEqualTo(expected)
    }

    @Test
    fun `recovery file never contains a plaintext secret or the raw DEK`() {
        val dir = dir()
        val dekEncoded = setUpAllThree(dir, pin = "1234", answer = "Fluffy", code = "7K9F-2XQP-4MRT-8WVN")

        val onDisk = recoveryFile(dir).readText()
        assertThat(onDisk).doesNotContain("Fluffy")
        assertThat(onDisk).doesNotContain("fluffy")
        assertThat(onDisk).doesNotContain("7K9F")
        assertThat(onDisk).doesNotContain("7K9F2XQP4MRT8WVN")
        val rawHex = dekEncoded.joinToString("") { "%02x".format(it) }
        assertThat(onDisk).doesNotContain(rawHex)
    }

    @Test
    fun `wrong answer and wrong code fail the tag`() {
        val dir = dir()
        setUpAllThree(dir, pin = "1234", answer = "Fluffy", code = "7K9F-2XQP-4MRT-8WVN")
        val envelope = RecoveryEnvelope(recoveryFile(dir))

        try {
            envelope.unlockWithSecurityAnswer("Rex")
            throw AssertionError("Expected WrongSecretException for a bad answer")
        } catch (expected: SecretKeyWrap.WrongSecretException) {
            // Correct.
        }
        try {
            envelope.unlockWithRecoveryCode("AAAA-BBBB-CCCC-DDDD")
            throw AssertionError("Expected WrongSecretException for a bad code")
        } catch (expected: SecretKeyWrap.WrongSecretException) {
            // Correct.
        }
    }

    @Test
    fun `an unconfigured recovery path is distinguishable from a wrong secret`() {
        val dir = dir()
        // Only the security answer is configured; the recovery code was never set.
        val dek = VaultKeyFile(keyFile(dir)).unlockOrCreate("1234")
        val envelope = RecoveryEnvelope(recoveryFile(dir))
        envelope.setSecurityAnswer(dek, "Fluffy")

        assertThat(envelope.hasSecurityAnswer()).isTrue()
        assertThat(envelope.hasRecoveryCode()).isFalse()
        assertThat(envelope.isFullyConfigured()).isFalse()
        try {
            envelope.unlockWithRecoveryCode("7K9F-2XQP-4MRT-8WVN")
            throw AssertionError("Expected SlotNotConfiguredException")
        } catch (expected: RecoveryEnvelope.SlotNotConfiguredException) {
            // Correct: a not-set-up path is reported distinctly from a wrong code.
        }
    }

    @Test
    fun `setting one wrap preserves the other`() {
        val dir = dir()
        val dek = VaultKeyFile(keyFile(dir)).unlockOrCreate("1234")
        val envelope = RecoveryEnvelope(recoveryFile(dir))

        envelope.setSecurityAnswer(dek, "Fluffy")
        envelope.setRecoveryCode(dek, "7K9F-2XQP-4MRT-8WVN")

        // A fresh instance still sees both, and both unwrap the DEK.
        val reread = RecoveryEnvelope(recoveryFile(dir))
        assertThat(reread.isFullyConfigured()).isTrue()
        assertThat(reread.unlockWithSecurityAnswer("Fluffy").encoded).isEqualTo(dek.encoded)
        assertThat(reread.unlockWithRecoveryCode("7K9F-2XQP-4MRT-8WVN").encoded).isEqualTo(dek.encoded)
    }

    @Test
    fun `a PIN change does not invalidate the recovery wraps`() {
        val dir = dir()
        val expected = setUpAllThree(dir, pin = "1111", answer = "Fluffy", code = "7K9F-2XQP-4MRT-8WVN")

        // Change the PIN — this re-wraps Wrap A only.
        VaultKeyFile(keyFile(dir)).rewrap("1111", "2222")

        // Recovery still works because Wrap B / Wrap C wrap the same unchanged DEK.
        assertThat(VaultKeyFile(keyFile(dir)).unlock("2222").encoded).isEqualTo(expected)
        assertThat(RecoveryEnvelope(recoveryFile(dir)).unlockWithSecurityAnswer("Fluffy").encoded).isEqualTo(expected)
        assertThat(RecoveryEnvelope(recoveryFile(dir)).unlockWithRecoveryCode("7K9F-2XQP-4MRT-8WVN").encoded)
            .isEqualTo(expected)
    }

    @Test
    fun `recovery re-wraps Wrap A only and leaves files readable (no bulk re-encrypt)`() {
        val dir = dir()
        val secret = "APP-322 :: sealed before recovery, readable after".toByteArray()

        // Set up the vault + recovery, and seal a blob under the original DEK.
        val dek = VaultKeyFile(keyFile(dir)).unlockOrCreate("1111")
        RecoveryEnvelope(recoveryFile(dir)).setRecoveryCode(dek, "7K9F-2XQP-4MRT-8WVN")
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dek).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        // Forgot the PIN → recover via the code, then set a NEW PIN (re-create Wrap A only).
        val recoveredDek = RecoveryEnvelope(recoveryFile(dir)).unlockWithRecoveryCode("7K9F-2XQP-4MRT-8WVN")
        VaultKeyFile(keyFile(dir)).writePinWrap(recoveredDek, "9999")

        // The new PIN unlocks the SAME DEK, and the pre-recovery blob still decrypts — files
        // were never re-encrypted.
        val viaNewPin = VaultKeyFile(keyFile(dir)).unlock("9999")
        assertThat(viaNewPin.encoded).isEqualTo(dek.encoded)
        val recovered =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(viaNewPin).decrypt(ByteArrayInputStream(blob), out) }
                .toByteArray()
        assertThat(recovered).isEqualTo(secret)

        // The old PIN is gone; the recovery code is untouched and still opens the vault.
        try {
            VaultKeyFile(keyFile(dir)).unlock("1111")
            throw AssertionError("Old PIN must no longer unlock after a recovery reset")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct.
        }
        assertThat(RecoveryEnvelope(recoveryFile(dir)).unlockWithRecoveryCode("7K9F-2XQP-4MRT-8WVN").encoded)
            .isEqualTo(dek.encoded)
    }

    @Test
    fun `regenerating the recovery code retires the old one and keeps the answer`() {
        val dir = dir()
        val expected = setUpAllThree(dir, pin = "1234", answer = "Fluffy", code = "OLD1-OLD2-OLD3-OLD4")

        RecoveryEnvelope(recoveryFile(dir)).setRecoveryCode(
            VaultKeyFile(keyFile(dir)).unlock("1234"),
            "NEW1-NEW2-NEW3-NEW4",
        )

        val envelope = RecoveryEnvelope(recoveryFile(dir))
        // New code works, old code no longer does, security answer is unaffected.
        assertThat(envelope.unlockWithRecoveryCode("NEW1-NEW2-NEW3-NEW4").encoded).isEqualTo(expected)
        try {
            envelope.unlockWithRecoveryCode("OLD1-OLD2-OLD3-OLD4")
            throw AssertionError("The retired recovery code must not unlock")
        } catch (expected: SecretKeyWrap.WrongSecretException) {
            // Correct.
        }
        assertThat(envelope.unlockWithSecurityAnswer("Fluffy").encoded).isEqualTo(expected)
    }

    @Test
    fun `writes are atomic and leave no temp file`() {
        val dir = dir()
        setUpAllThree(dir, pin = "1234", answer = "Fluffy", code = "7K9F-2XQP-4MRT-8WVN")

        assertThat(dir.listFiles()!!.map { it.name }).containsExactly(".vaultkey", ".vaultrecovery")
    }

    @Test
    fun `all-secrets-lost is unrecoverable by design (no backdoor)`() {
        val dir = dir()
        setUpAllThree(dir, pin = "1234", answer = "Fluffy", code = "7K9F-2XQP-4MRT-8WVN")
        val envelope = RecoveryEnvelope(recoveryFile(dir))

        // There is no fourth path: every wrong secret fails, and nothing on disk yields the DEK.
        try {
            VaultKeyFile(keyFile(dir)).unlock("0000")
            throw AssertionError("A wrong PIN must not unlock")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct.
        }
        try {
            envelope.unlockWithSecurityAnswer("nope")
            throw AssertionError("A wrong answer must not unlock")
        } catch (expected: SecretKeyWrap.WrongSecretException) {
            // Correct.
        }
        try {
            envelope.unlockWithRecoveryCode("ZZZZ-ZZZZ-ZZZZ-ZZZZ")
            throw AssertionError("A wrong code must not unlock")
        } catch (expected: SecretKeyWrap.WrongSecretException) {
            // Correct.
        }
    }
}
