package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Off-device proof of the change-PIN envelope re-key (APP-245). Changing the PIN must move
 * the `.vaultkey` wrapping — not the data key — so every blob hidden before the change stays
 * readable after it, the new PIN is the only one that unwraps, and a failed re-key leaves
 * the original envelope byte-for-byte intact (no half-written key file can ever strand the
 * vault).
 */
class VaultKeyFileRewrapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun keyFile(): File = File(tmp.newFolder(".CalcVault"), ".vaultkey")

    @Test
    fun `rewrap preserves the data key across a pin change`() {
        val file = keyFile()
        val dekBefore = VaultKeyFile(file).unlockOrCreate("1111")

        VaultKeyFile(file).rewrap("1111", "2222")

        // A fresh instance (no in-memory state) unwraps the SAME key with the new PIN.
        val dekAfter = VaultKeyFile(file).unlock("2222")
        assertThat(dekAfter.encoded).isEqualTo(dekBefore.encoded)
    }

    @Test
    fun `blobs hidden before the pin change decrypt after it`() {
        val file = keyFile()
        val secret = "APP-245 :: hidden before the PIN change, readable after".toByteArray()
        val dekBefore = VaultKeyFile(file).unlockOrCreate("1111")
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dekBefore).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        VaultKeyFile(file).rewrap("1111", "2222")

        val dekAfter = VaultKeyFile(file).unlock("2222")
        val recovered =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dekAfter).decrypt(ByteArrayInputStream(blob), out) }
                .toByteArray()
        assertThat(recovered).isEqualTo(secret)
    }

    @Test
    fun `old pin no longer unwraps after rewrap`() {
        val file = keyFile()
        VaultKeyFile(file).unlockOrCreate("1111")

        VaultKeyFile(file).rewrap("1111", "2222")

        try {
            VaultKeyFile(file).unlock("1111")
            throw AssertionError("Expected WrongPassphraseException for the retired PIN")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct: the retired PIN fails the GCM tag exactly like any wrong passphrase.
        }
    }

    @Test
    fun `wrong old pin throws and leaves the envelope intact`() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate("1111")
        val envelopeBefore = file.readText()

        try {
            VaultKeyFile(file).rewrap("9999", "2222")
            throw AssertionError("Expected WrongPassphraseException for a bad old PIN")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct: no key was unwrapped, so nothing may have been rewritten.
        }

        assertThat(file.readText()).isEqualTo(envelopeBefore)
        assertThat(VaultKeyFile(file).unlock("1111").encoded).isEqualTo(dek.encoded)
        // And the attempted new PIN gained nothing.
        try {
            VaultKeyFile(file).unlock("2222")
            throw AssertionError("A failed rewrap must not honor the new PIN")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct.
        }
    }

    @Test
    fun `rewrap uses a fresh salt and iv and leaves no temp file`() {
        val file = keyFile()
        VaultKeyFile(file).unlockOrCreate("1111")
        val partsBefore = file.readText().trim().split(":")

        VaultKeyFile(file).rewrap("1111", "1111")

        // Same passphrase, but salt (index 2) and IV (index 3) must still rotate.
        val partsAfter = file.readText().trim().split(":")
        assertThat(partsAfter[2]).isNotEqualTo(partsBefore[2])
        assertThat(partsAfter[3]).isNotEqualTo(partsBefore[3])
        // The atomic temp+rename write leaves no sibling behind.
        assertThat(file.parentFile!!.listFiles()!!.map { it.name }).containsExactly(".vaultkey")
    }

    @Test
    fun `rewrap without a key file is refused`() {
        val file = keyFile()
        try {
            VaultKeyFile(file).rewrap("1111", "2222")
            throw AssertionError("Expected IllegalStateException with no key file")
        } catch (expected: IllegalStateException) {
            // Correct: with nothing wrapped there is nothing to re-key; callers gate on exists().
        }
        assertThat(file.exists()).isFalse()
    }
}
