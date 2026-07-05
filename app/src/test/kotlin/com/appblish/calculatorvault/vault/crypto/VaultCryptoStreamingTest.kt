package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Bulk-op hardening (spec §11): the streaming rework must (a) round-trip payloads far
 * larger than any internal buffer and (b) keep the on-disk wire format —
 * [12-byte IV ‖ AES-256-GCM ciphertext+tag] — byte-identical to what the pre-rework
 * one-shot `doFinal` path produced, so every blob already sitting in .CalcVault/ still
 * decrypts. The cross-compat tests reproduce that legacy path with a raw [Cipher].
 */
class VaultCryptoStreamingTest {
    private val key = VaultCrypto.newKey()
    private val crypto = VaultCrypto(key)

    /** Deterministic multi-MB payload — spans many 64 KiB stream buffers. */
    private fun bigPayload(sizeBytes: Int): ByteArray = ByteArray(sizeBytes).also { Random(42L).nextBytes(it) }

    @Test
    fun `multi-megabyte payload round-trips through the streaming encrypt and decrypt`() {
        val plain = bigPayload(8 * 1024 * 1024)

        val cipherOut = ByteArrayOutputStream()
        crypto.encrypt(ByteArrayInputStream(plain), cipherOut)
        val blob = cipherOut.toByteArray()
        // IV prefix + GCM tag overhead, and definitely not the plaintext.
        assertThat(blob.size).isAtLeast(plain.size + 12 + 16)

        val plainOut = ByteArrayOutputStream()
        crypto.decrypt(ByteArrayInputStream(blob), plainOut)
        assertThat(plainOut.toByteArray()).isEqualTo(plain)
    }

    @Test
    fun `streaming decrypt reads a blob produced by the legacy one-shot cipher`() {
        // Blobs written before the streaming rework were [iv || doFinal(plain)].
        val plain = bigPayload(2 * 1024 * 1024)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val oneShot =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        val legacyBlob = iv + oneShot.doFinal(plain)

        val out = ByteArrayOutputStream()
        crypto.decrypt(ByteArrayInputStream(legacyBlob), out)
        assertThat(out.toByteArray()).isEqualTo(plain)
    }

    @Test
    fun `one-shot cipher reads a blob produced by the streaming encrypt`() {
        // The reverse direction proves new blobs stay readable by any byte-based consumer.
        val plain = bigPayload(2 * 1024 * 1024)
        val cipherOut = ByteArrayOutputStream()
        crypto.encrypt(ByteArrayInputStream(plain), cipherOut)
        val blob = cipherOut.toByteArray()

        val iv = blob.copyOfRange(0, 12)
        val oneShot =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        assertThat(oneShot.doFinal(blob, 12, blob.size - 12)).isEqualTo(plain)
    }

    @Test
    fun `tampering with a large streamed blob fails the GCM tag`() {
        val cipherOut = ByteArrayOutputStream()
        crypto.encrypt(ByteArrayInputStream(bigPayload(1024 * 1024)), cipherOut)
        val blob = cipherOut.toByteArray()
        blob[blob.size / 2] = (blob[blob.size / 2] + 1).toByte()

        var threw = false
        try {
            crypto.decrypt(ByteArrayInputStream(blob), ByteArrayOutputStream())
        } catch (e: Exception) {
            // CipherInputStream surfaces AEADBadTagException as an IOException on the
            // final read — either way the corrupted blob must never decrypt silently.
            threw = true
        }
        assertThat(threw).isTrue()
    }
}
