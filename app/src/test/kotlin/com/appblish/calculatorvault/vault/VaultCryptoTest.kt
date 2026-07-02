package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VaultCryptoTest {
    @Test
    fun `encrypt then decrypt recovers the original bytes`() {
        val crypto = VaultCrypto(VaultCrypto.newKey())
        val plain = "payslip PDF contents 🔐".toByteArray()

        val cipherOut = ByteArrayOutputStream()
        crypto.encrypt(ByteArrayInputStream(plain), cipherOut)
        val cipher = cipherOut.toByteArray()

        // Ciphertext must differ from plaintext and carry the 12-byte IV + 16-byte tag.
        assertThat(cipher).isNotEqualTo(plain)
        assertThat(cipher.size).isAtLeast(plain.size + 12 + 16)

        val plainOut = ByteArrayOutputStream()
        crypto.decrypt(ByteArrayInputStream(cipher), plainOut)
        assertThat(plainOut.toByteArray()).isEqualTo(plain)
    }

    @Test
    fun `tampered ciphertext fails the GCM tag`() {
        val crypto = VaultCrypto(VaultCrypto.newKey())
        val cipherOut = ByteArrayOutputStream()
        crypto.encrypt(ByteArrayInputStream("secret".toByteArray()), cipherOut)
        val cipher = cipherOut.toByteArray()
        cipher[cipher.lastIndex] = (cipher[cipher.lastIndex] + 1).toByte()

        var threw = false
        try {
            crypto.decrypt(ByteArrayInputStream(cipher), ByteArrayOutputStream())
        } catch (e: Exception) {
            threw = true
        }
        assertThat(threw).isTrue()
    }
}
