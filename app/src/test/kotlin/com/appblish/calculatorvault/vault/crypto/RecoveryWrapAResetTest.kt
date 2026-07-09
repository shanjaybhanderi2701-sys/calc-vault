package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Off-device proof of the **recovery PIN reset** crypto contract (PIN Recovery W3, spec §5.2):
 * a forgotten PIN is reset by unwrapping the DEK via **Wrap B** (security answer) or **Wrap C**
 * (recovery code) and then re-creating **Wrap A only** under the new PIN. This test pins the
 * three DoD invariants at the envelope level, exactly as [VaultKeyFileRecoveryPinReset] drives it:
 *
 *  - (a) each recovery path resets the PIN — the new PIN unwraps the SAME immutable DEK;
 *  - (b) the reset does NOT break Wrap B / Wrap C — both still unwrap that same DEK afterwards;
 *  - (c) files are never re-encrypted — a blob sealed before the reset decrypts unchanged after.
 */
class RecoveryWrapAResetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val oldPin = "1234"
    private val newPin = "9999"
    private val answer = "Fluffy The Cat"
    private val code = "7K9F-2XQP-4MRT-8WVN"

    private fun keyFile(): File = File(tmp.newFolder(".CalcVault"), ".vaultkey")

    /** Set up a vault + recovery wraps and seal a blob; return (file, DEK, blob, plaintext). */
    private fun freshRecoverableVault(): Vault {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate(oldPin)
        VaultKeyFile(file).setUpRecovery(oldPin, answer, code)
        val plaintext = "APP-325 :: sealed before the recovery reset, readable after".toByteArray()
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dek).encrypt(ByteArrayInputStream(plaintext), out) }
                .toByteArray()
        return Vault(file, dek, blob, plaintext)
    }

    private class Vault(
        val file: File,
        val dek: javax.crypto.SecretKey,
        val blob: ByteArray,
        val plaintext: ByteArray,
    )

    @Test
    fun `security-answer path resets the pin via wrap-A-only re-wrap, B and C survive`() {
        val vault = freshRecoverableVault()

        // Forgot PIN → prove identity via Wrap B, then set a new PIN (Wrap A only).
        val recovered = VaultKeyFile(vault.file).unlockWithAnswer(answer)
        assertThat(recovered.encoded).isEqualTo(vault.dek.encoded)
        VaultKeyFile(vault.file).replacePinWrap(recovered, newPin)

        assertResetInvariants(vault)
    }

    @Test
    fun `recovery-code path resets the pin via wrap-A-only re-wrap, B and C survive`() {
        val vault = freshRecoverableVault()

        val recovered = VaultKeyFile(vault.file).unlockWithRecoveryCode(code)
        assertThat(recovered.encoded).isEqualTo(vault.dek.encoded)
        VaultKeyFile(vault.file).replacePinWrap(recovered, newPin)

        assertResetInvariants(vault)
    }

    /** The shared DoD assertions after a Wrap-A-only reset, from a fresh (stateless) instance. */
    private fun assertResetInvariants(vault: Vault) {
        // (a) The NEW pin unwraps the same DEK; the OLD pin is dead like any wrong passphrase.
        assertThat(VaultKeyFile(vault.file).unlock(newPin).encoded).isEqualTo(vault.dek.encoded)
        try {
            VaultKeyFile(vault.file).unlock(oldPin)
            throw AssertionError("The forgotten PIN must stop unwrapping after a recovery reset")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct.
        }

        // (b) A PIN change re-wraps Wrap A only — both recovery wraps still unwrap the SAME DEK.
        assertThat(VaultKeyFile(vault.file).unlockWithAnswer(answer).encoded).isEqualTo(vault.dek.encoded)
        assertThat(VaultKeyFile(vault.file).unlockWithRecoveryCode(code).encoded).isEqualTo(vault.dek.encoded)
        assertThat(VaultKeyFile(vault.file).isRecoveryConfigured()).isTrue()

        // (c) The blob sealed under the OLD pin decrypts byte-for-byte under the reset vault —
        // the DEK never changed, so no file was ever re-encrypted.
        val dekAfter = VaultKeyFile(vault.file).unlock(newPin)
        val recoveredBytes =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dekAfter).decrypt(ByteArrayInputStream(vault.blob), out) }
                .toByteArray()
        assertThat(recoveredBytes).isEqualTo(vault.plaintext)
    }

    @Test
    fun `a wrong answer never unwraps and cannot reset the pin`() {
        val vault = freshRecoverableVault()
        val envelopeBefore = vault.file.readText()

        try {
            VaultKeyFile(vault.file).unlockWithAnswer("not my pet")
            throw AssertionError("A wrong security answer must fail the GCM tag")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct: no DEK, so the caller can never reach replacePinWrap.
        }
        // Nothing was rewritten by a failed verify.
        assertThat(vault.file.readText()).isEqualTo(envelopeBefore)
    }
}
