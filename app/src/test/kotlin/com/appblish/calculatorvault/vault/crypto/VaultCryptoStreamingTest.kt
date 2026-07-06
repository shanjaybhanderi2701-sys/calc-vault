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
 * Bulk-op hardening (spec §11) + chunked rework (APP-244): encryption must (a) round-trip
 * payloads far larger than any internal buffer and (b) still *read* every blob already
 * sitting in .CalcVault/ — the pre-APP-244 v1 wire format [12-byte IV ‖ AES-256-GCM
 * ciphertext+tag] — while new blobs are written in the chunked v2 format whose peak
 * memory is one chunk (Android's Conscrypt GCM is a one-shot AEAD, so v1 "streaming"
 * still buffered whole files on device). The legacy-compat test reproduces the v1 writer
 * with a raw [Cipher].
 */
class VaultCryptoStreamingTest {
    private val key = VaultCrypto.newKey()
    private val crypto = VaultCrypto(key)

    /** Deterministic multi-MB payload — spans many chunks. */
    private fun bigPayload(sizeBytes: Int): ByteArray = ByteArray(sizeBytes).also { Random(42L).nextBytes(it) }

    @Test
    fun `multi-megabyte payload round-trips through the streaming encrypt and decrypt`() {
        val plain = bigPayload(8 * 1024 * 1024)

        val cipherOut = ByteArrayOutputStream()
        crypto.encrypt(ByteArrayInputStream(plain), cipherOut)
        val blob = cipherOut.toByteArray()
        // Header + per-chunk GCM tag overhead, and definitely not the plaintext.
        assertThat(blob.size).isAtLeast(plain.size + 12 + 16)

        val plainOut = ByteArrayOutputStream()
        crypto.decrypt(ByteArrayInputStream(blob), plainOut)
        assertThat(plainOut.toByteArray()).isEqualTo(plain)
    }

    @Test
    fun `decrypt reads a legacy v1 blob produced by the pre-APP-244 one-shot cipher`() {
        // Every blob/index written before APP-244 was [iv || doFinal(plain)] — a vault
        // hidden by an older build must keep opening after the app updates.
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
    fun `new blobs carry the chunked v2 magic so readers can version-sniff`() {
        val cipherOut = ByteArrayOutputStream()
        crypto.encrypt(ByteArrayInputStream("hello".toByteArray()), cipherOut)
        val blob = cipherOut.toByteArray()
        assertThat(blob.copyOfRange(0, VaultCrypto.MAGIC.size)).isEqualTo(VaultCrypto.MAGIC)
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
            // A corrupted blob must never decrypt silently.
            threw = true
        }
        assertThat(threw).isTrue()
    }
}
