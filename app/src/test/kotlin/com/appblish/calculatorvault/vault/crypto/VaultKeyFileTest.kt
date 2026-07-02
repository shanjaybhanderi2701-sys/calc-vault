package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Off-device proof of the PIN-recoverable key envelope (APP-169). This is the crux of the
 * survive-uninstall directive: after a reinstall the app has no keystore material — the
 * ONLY way it can read the vault is by re-deriving the data key from the PIN plus the
 * `.vaultkey` file left on public storage. These tests exercise exactly that, with a fresh
 * [VaultKeyFile] instance standing in for the reinstalled app.
 */
class VaultKeyFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun keyFile(): File = File(tmp.newFolder(".CalcVault"), ".vaultkey")

    @Test
    fun `same pin re-derives the same data key across fresh instances (reinstall)`() {
        val file = keyFile()

        // Install #1: create + wrap the DEK under the PIN.
        val dek1 = VaultKeyFile(file).unlockOrCreate("1234")
        assertThat(file.exists()).isTrue()

        // Reinstall: a brand-new instance over the same file, no in-memory state.
        val dek2 = VaultKeyFile(file).unlock("1234")

        assertThat(dek2.encoded).isEqualTo(dek1.encoded)
    }

    @Test
    fun `data encrypted before reinstall decrypts after reinstall with the pin`() {
        val file = keyFile()
        val secret = "APP-169 blob payload :: not lost on uninstall".toByteArray()

        // Install #1: encrypt a blob with the DEK.
        val dek1 = VaultKeyFile(file).unlockOrCreate("1234")
        val blob =
            ByteArrayOutputStream()
                .also { out ->
                    VaultCrypto(dek1).encrypt(ByteArrayInputStream(secret), out)
                }.toByteArray()

        // Reinstall: recover the DEK from the PIN + key file, decrypt the same blob.
        val dek2 = VaultKeyFile(file).unlock("1234")
        val recovered =
            ByteArrayOutputStream()
                .also { out ->
                    VaultCrypto(dek2).decrypt(ByteArrayInputStream(blob), out)
                }.toByteArray()

        assertThat(recovered).isEqualTo(secret)
    }

    @Test
    fun `wrong pin cannot unwrap the key`() {
        val file = keyFile()
        VaultKeyFile(file).unlockOrCreate("1234")

        try {
            VaultKeyFile(file).unlock("9999")
            throw AssertionError("Expected WrongPassphraseException for a bad PIN")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct: a wrong PIN fails the GCM tag and never yields a key.
        }
    }

    @Test
    fun `key file never contains the raw data key`() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate("1234")

        val onDisk = file.readText()
        // The wrapped envelope must not expose the raw key material as hex.
        val rawHex = dek.encoded.joinToString("") { "%02x".format(it) }
        assertThat(onDisk).doesNotContain(rawHex)
    }
}
