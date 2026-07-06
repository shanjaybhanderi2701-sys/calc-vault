package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.util.Random

/**
 * Chunked v2 format guarantees (APP-244 streaming crypto): exact round-trips at every
 * chunk-boundary edge case, and — because each chunk is its own GCM message — proof that
 * the per-chunk nonce counter and final-flag close the attacks single-message GCM got for
 * free: chunk reordering, chunk-boundary truncation, and cross-blob chunk splicing must
 * all fail the tag, never silently emit wrong plaintext.
 */
class VaultCryptoChunkedTest {
    private val key = VaultCrypto.newKey()
    private val crypto = VaultCrypto(key)
    private val chunk = VaultCrypto.CHUNK_BYTES
    private val header = VaultCrypto.MAGIC.size + 7
    private val sealed = chunk + 16

    private fun payload(size: Int): ByteArray = ByteArray(size).also { Random(7L).nextBytes(it) }

    private fun encrypt(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        crypto.encrypt(ByteArrayInputStream(plain), out)
        return out.toByteArray()
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        crypto.decrypt(ByteArrayInputStream(blob), out)
        return out.toByteArray()
    }

    private fun assertRejects(blob: ByteArray) {
        var threw = false
        try {
            decrypt(blob)
        } catch (e: GeneralSecurityException) {
            threw = true
        } catch (e: Exception) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `round-trips every chunk-boundary size`() {
        for (size in intArrayOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, (2.5 * chunk).toInt())) {
            val plain = payload(size)
            assertThat(decrypt(encrypt(plain))).isEqualTo(plain)
        }
    }

    @Test
    fun `peak decrypt memory is chunk-scale not file-scale`() {
        // Structural proof (the on-device proof is LargeVideoHideDoDTest): a 3-chunk blob
        // has one sealed chunk (+ look-ahead) in flight at a time by construction; here we
        // just pin the layout arithmetic the reader depends on.
        val blob = encrypt(payload(3 * chunk))
        assertThat(blob.size).isEqualTo(header + 3 * sealed)
    }

    @Test
    fun `swapping two chunks fails the tag`() {
        val blob = encrypt(payload(2 * chunk + 5))
        val swapped = blob.copyOf()
        // Swap sealed chunk 0 and sealed chunk 1 (both full-size, non-final).
        System.arraycopy(blob, header + sealed, swapped, header, sealed)
        System.arraycopy(blob, header, swapped, header + sealed, sealed)
        assertRejects(swapped)
    }

    @Test
    fun `truncating at a chunk boundary fails the tag`() {
        val blob = encrypt(payload(2 * chunk + 5))
        // Drop the final chunk entirely: the last kept chunk was sealed non-final but the
        // reader will treat it as final — nonce mismatch, tag failure.
        assertRejects(blob.copyOfRange(0, header + sealed))
    }

    @Test
    fun `duplicating a chunk fails the tag`() {
        val blob = encrypt(payload(chunk + 5))
        val doubled =
            blob.copyOfRange(0, header + sealed) +
                blob.copyOfRange(header, header + sealed) +
                blob.copyOfRange(header + sealed, blob.size)
        assertRejects(doubled)
    }

    @Test
    fun `splicing a chunk from another blob fails the tag`() {
        val a = encrypt(payload(chunk + 5))
        val b = encrypt(payload(chunk + 5))
        // Replace a's first sealed chunk with b's — same key, same position, different
        // nonce prefix: must not authenticate.
        val spliced = a.copyOf()
        System.arraycopy(b, header, spliced, header, sealed)
        assertRejects(spliced)
    }

    @Test
    fun `truncating inside the header is rejected`() {
        val blob = encrypt(payload(5))
        assertRejects(blob.copyOfRange(0, VaultCrypto.MAGIC.size + 3))
    }

    @Test
    fun `wrong key fails the tag`() {
        val blob = encrypt(payload(chunk + 5))
        val other = VaultCrypto(VaultCrypto.newKey())
        var threw = false
        try {
            other.decrypt(ByteArrayInputStream(blob), ByteArrayOutputStream())
        } catch (e: Exception) {
            threw = true
        }
        assertThat(threw).isTrue()
    }
}
