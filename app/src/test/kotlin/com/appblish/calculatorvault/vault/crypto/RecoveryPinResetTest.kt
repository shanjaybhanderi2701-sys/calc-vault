package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Off-device proof of the APP-325 W3 recovery reset: proving identity with the security
 * answer (Wrap B) or the recovery code (Wrap C) unwraps the DEK and re-wraps **Wrap A only**
 * under a new PIN. Covers the DoD directly:
 *
 *  - (a) each recovery path resets the PIN via a Wrap-A-only re-wrap,
 *  - (b) the reset does NOT break Wrap B / Wrap C — both keep unwrapping the same DEK,
 *  - (c) files are never re-encrypted — the DEK is unchanged, so a blob hidden before the
 *        reset still decrypts after it, and the Wrap B/C bytes on disk are untouched.
 *
 * All pure JVM (PBKDF2 + AES/GCM), so the whole reset model is verifiable without a device.
 */
class RecoveryPinResetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun keyFile(): File = File(tmp.newFolder(".CalcVault"), ".vaultkey")

    /** A vault set up with a PIN and both recovery wraps configured. */
    private fun setUpConfiguredVault(file: File): javax.crypto.SecretKey {
        val dek = VaultKeyFile(file).unlockOrCreate(PIN)
        VaultKeyFile(file).setUpRecovery(PIN, ANSWER, CODE)
        return dek
    }

    private fun slotLine(
        file: File,
        prefix: String,
    ): String? = file.readLines().firstOrNull { it.startsWith("$prefix:") }

    @Test
    fun `answer path resets the pin via wrap A only`() {
        val file = keyFile()
        val dek = setUpConfiguredVault(file)

        val outcome = RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.SECURITY_ANSWER, ANSWER, NEW_PIN)

        assertThat(outcome).isEqualTo(RecoveryResetOutcome.RESET)
        // (a) the new PIN unwraps the SAME immutable DEK; the old PIN no longer does.
        assertThat(VaultKeyFile(file).unlock(NEW_PIN).encoded).isEqualTo(dek.encoded)
        assertThatWrongPassphrase { VaultKeyFile(file).unlock(PIN) }
    }

    @Test
    fun `code path resets the pin via wrap A only`() {
        val file = keyFile()
        val dek = setUpConfiguredVault(file)

        val outcome = RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.RECOVERY_CODE, CODE, NEW_PIN)

        assertThat(outcome).isEqualTo(RecoveryResetOutcome.RESET)
        assertThat(VaultKeyFile(file).unlock(NEW_PIN).encoded).isEqualTo(dek.encoded)
        assertThatWrongPassphrase { VaultKeyFile(file).unlock(PIN) }
    }

    @Test
    fun `reset does not break wrap B or wrap C`() {
        val file = keyFile()
        val dek = setUpConfiguredVault(file)

        RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.SECURITY_ANSWER, ANSWER, NEW_PIN)

        // (b) BOTH recovery paths still unwrap the same DEK after the PIN reset — a reset via
        // the answer must not invalidate the recovery code, nor the answer itself.
        assertThat(VaultKeyFile(file).unlockWithAnswer(ANSWER).encoded).isEqualTo(dek.encoded)
        assertThat(VaultKeyFile(file).unlockWithRecoveryCode(CODE).encoded).isEqualTo(dek.encoded)
        assertThat(VaultKeyFile(file).isRecoveryConfigured()).isTrue()
    }

    @Test
    fun `reset leaves the wrap B and wrap C bytes untouched on disk`() {
        val file = keyFile()
        setUpConfiguredVault(file)
        val bBefore = slotLine(file, "B")
        val cBefore = slotLine(file, "C")
        val aBefore = slotLine(file, "A")

        RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.RECOVERY_CODE, CODE, NEW_PIN)

        // (c) only Wrap A is rewritten; the recovery slots are byte-for-byte identical, proving
        // nothing beyond the PIN wrap was re-derived or re-encrypted.
        assertThat(slotLine(file, "B")).isEqualTo(bBefore)
        assertThat(slotLine(file, "C")).isEqualTo(cBefore)
        assertThat(slotLine(file, "A")).isNotEqualTo(aBefore)
    }

    @Test
    fun `blob hidden before the reset decrypts after it`() {
        val file = keyFile()
        val dekBefore = setUpConfiguredVault(file)
        val secret = "APP-325 :: hidden before the recovery reset, readable after".toByteArray()
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dekBefore).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.SECURITY_ANSWER, ANSWER, NEW_PIN)

        // (c) the DEK never changed, so the pre-reset blob still decrypts — no bulk re-encrypt.
        val dekAfter = VaultKeyFile(file).unlock(NEW_PIN)
        val recovered =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dekAfter).decrypt(ByteArrayInputStream(blob), out) }
                .toByteArray()
        assertThat(recovered).isEqualTo(secret)
    }

    @Test
    fun `wrong secret is rejected and leaves the envelope intact`() {
        val file = keyFile()
        setUpConfiguredVault(file)
        val before = file.readText()

        val outcome =
            RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.RECOVERY_CODE, "WRONG-CODE-XXXX-YYYY", NEW_PIN)

        assertThat(outcome).isEqualTo(RecoveryResetOutcome.WRONG_SECRET)
        // A wrong secret must not touch the key file at all, and the original PIN still works.
        assertThat(file.readText()).isEqualTo(before)
        assertThat(VaultKeyFile(file).unlock(PIN)).isNotNull()
    }

    @Test
    fun `reset before recovery is configured reports not configured`() {
        val file = keyFile()
        VaultKeyFile(file).unlockOrCreate(PIN) // PIN wrap only, no B/C

        val outcome = RecoveryEnvelope.resetPin(VaultKeyFile(file), RecoveryMethod.SECURITY_ANSWER, ANSWER, NEW_PIN)

        assertThat(outcome).isEqualTo(RecoveryResetOutcome.NOT_CONFIGURED)
    }

    @Test
    fun `verify distinguishes correct, wrong, and unconfigured`() {
        val file = keyFile()
        setUpConfiguredVault(file)

        assertThat(RecoveryEnvelope.verify(VaultKeyFile(file), RecoveryMethod.SECURITY_ANSWER, ANSWER))
            .isEqualTo(RecoveryVerifyOutcome.CORRECT)
        assertThat(RecoveryEnvelope.verify(VaultKeyFile(file), RecoveryMethod.SECURITY_ANSWER, "nope"))
            .isEqualTo(RecoveryVerifyOutcome.WRONG_SECRET)

        val fresh = keyFile()
        VaultKeyFile(fresh).unlockOrCreate(PIN)
        assertThat(RecoveryEnvelope.verify(VaultKeyFile(fresh), RecoveryMethod.RECOVERY_CODE, CODE))
            .isEqualTo(RecoveryVerifyOutcome.NOT_CONFIGURED)
    }

    private fun assertThatWrongPassphrase(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected WrongPassphraseException")
        } catch (e: VaultKeyFile.WrongPassphraseException) {
            // expected
        }
    }

    private companion object {
        const val PIN = "1234"
        const val NEW_PIN = "5678"
        const val ANSWER = "Fluffy The Cat"
        const val CODE = "7K9F-2XQP-4MRT-8WVN"
    }
}
