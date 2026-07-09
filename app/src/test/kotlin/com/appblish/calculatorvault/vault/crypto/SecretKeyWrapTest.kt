package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Off-device proof of the single key-wrapping primitive (APP-322 §1). Everything the vault
 * seals — the PIN wrap and both recovery wraps — goes through here, so these lock down the
 * guarantees the Security Engineer signs off: right secret unwraps, wrong secret fails, and
 * neither the raw DEK nor the secret ever appears in the serialized wrap.
 */
class SecretKeyWrapTest {
    @Test
    fun `wrap then unwrap with the same secret returns the same key`() {
        val dek = VaultCrypto.newKey()

        val serialized = SecretKeyWrap.wrap(dek, "correct horse")
        val recovered = SecretKeyWrap.unwrap(serialized, "correct horse")

        assertThat(recovered.encoded).isEqualTo(dek.encoded)
    }

    @Test
    fun `wrong secret fails the tag instead of returning a key`() {
        val serialized = SecretKeyWrap.wrap(VaultCrypto.newKey(), "right")

        try {
            SecretKeyWrap.unwrap(serialized, "wrong")
            throw AssertionError("Expected WrongSecretException")
        } catch (expected: SecretKeyWrap.WrongSecretException) {
            // Correct: AES-GCM authenticates, so a wrong KEK never yields plaintext.
        }
    }

    @Test
    fun `serialized wrap never contains the raw key or the secret`() {
        val dek = VaultCrypto.newKey()
        val secret = "super-secret-answer"

        val serialized = SecretKeyWrap.wrap(dek, secret)

        val rawHex = dek.encoded.joinToString("") { "%02x".format(it) }
        assertThat(serialized).doesNotContain(rawHex)
        assertThat(serialized).doesNotContain(secret)
    }

    @Test
    fun `two wraps of the same key under the same secret differ (fresh salt and iv)`() {
        val dek = VaultCrypto.newKey()

        val a = SecretKeyWrap.wrap(dek, "s")
        val b = SecretKeyWrap.wrap(dek, "s")

        // Different salt+IV each time, yet both unwrap to the same DEK.
        assertThat(a).isNotEqualTo(b)
        assertThat(SecretKeyWrap.unwrap(a, "s").encoded).isEqualTo(dek.encoded)
        assertThat(SecretKeyWrap.unwrap(b, "s").encoded).isEqualTo(dek.encoded)
    }

    @Test
    fun `malformed payload is rejected`() {
        try {
            SecretKeyWrap.unwrap("not:enough:fields", "s")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Correct: the payload must be exactly iterations:salt:iv:wrapped.
        }
    }
}
