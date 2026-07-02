package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

/**
 * Off-device proof of Phase-6 **decoy isolation** — the #1 integration risk flagged in the
 * spec (APP-198). Two distinct passphrases (the real PIN and a decoy PIN) must open two
 * completely separate vaults that can never read one another's content.
 *
 * The isolation is enforced at two independent layers, each asserted below:
 *  1. **Directory** — [VaultStorage] routes the real vault to the root `.CalcVault/` and
 *     each decoy to its own `.CalcVault/decoy_<slot>/`, so the key files and encrypted
 *     indexes never collide.
 *  2. **Key** — every vault has its own random data key wrapped under its own passphrase in
 *     its own `.vaultkey` ([VaultKeyFile]), so even given a decoy's directory an attacker
 *     with the decoy PIN cannot derive the real vault's key.
 *
 * All layers are pure JVM (hex-encoded envelope, `Mac`-based PBKDF2), so this runs in the
 * CI unit-test gate without an emulator.
 */
class DecoyIsolationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val realPin = "1234"
    private val decoyPin = "5678"
    private val realSecret = "REAL vault contents :: private".toByteArray()

    @Test
    fun `real and decoy resolve to different directories and key files`() {
        val base = tmp.newFolder("ext")
        val realDir = VaultStorage.vaultDir(base, namespace = "")
        val decoyDir = VaultStorage.vaultDir(base, namespace = "decoy_0")

        assertThat(realDir.absolutePath).isNotEqualTo(decoyDir.absolutePath)
        // The decoy dir is nested inside the root vault dir, not a sibling of it.
        assertThat(decoyDir.parentFile!!.absolutePath).isEqualTo(realDir.absolutePath)
        // The MediaStore marker sits at the root so it covers the decoy sub-dir too.
        assertThat(java.io.File(realDir, ".nomedia").exists()).isTrue()
    }

    @Test
    fun `a decoy passphrase cannot decrypt real vault content`() {
        val base = tmp.newFolder("ext")

        // Real vault: create its key + encrypt a secret blob in the root namespace.
        val realKeyFile = java.io.File(VaultStorage.vaultDir(base, namespace = ""), VaultStorage.KEY_NAME)
        val realDek = VaultKeyFile(realKeyFile).unlockOrCreate(realPin)
        val realBlob =
            ByteArrayOutputStream()
                .also { VaultCrypto(realDek).encrypt(ByteArrayInputStream(realSecret), it) }
                .toByteArray()

        // Decoy vault: its own key file in its own namespace, created under the decoy PIN.
        val decoyKeyFile = java.io.File(VaultStorage.vaultDir(base, namespace = "decoy_0"), VaultStorage.KEY_NAME)
        val decoyDek = VaultKeyFile(decoyKeyFile).unlockOrCreate(decoyPin)

        // The two data keys are independent random keys.
        assertThat(decoyDek.encoded).isNotEqualTo(realDek.encoded)

        // Decrypting the real blob with the decoy key must fail its GCM auth tag — no leak.
        var leaked = false
        try {
            ByteArrayOutputStream().use { out ->
                VaultCrypto(decoyDek).decrypt(ByteArrayInputStream(realBlob), out)
                leaked = out.toByteArray().contentEquals(realSecret)
            }
        } catch (expected: GeneralSecurityException) {
            leaked = false
        }
        assertThat(leaked).isFalse()
    }

    @Test
    fun `wrong passphrase on the same key file is rejected (APP-169 semantics preserved)`() {
        val base = tmp.newFolder("ext")
        val keyFile = java.io.File(VaultStorage.vaultDir(base, namespace = ""), VaultStorage.KEY_NAME)
        VaultKeyFile(keyFile).unlockOrCreate(realPin)

        try {
            VaultKeyFile(keyFile).unlock("0000")
            throw AssertionError("Expected a wrong-passphrase failure")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct: a non-matching PIN cannot unwrap the DEK for the same vault.
        }
    }
}
