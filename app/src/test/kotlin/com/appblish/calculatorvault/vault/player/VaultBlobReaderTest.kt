package com.appblish.calculatorvault.vault.player

import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Promotes the Gate-B seek spike (APP-346) into a repository regression: the #1 risk in the
 * whole video phase — arbitrary-offset decrypt without decrypting the whole file — proven
 * against the **production v2 wire format** written by [VaultCrypto], plus the v1 legacy
 * forward-only fallback (spec §5). Pure JVM (no Android / Media3), so it runs off-device.
 *
 * A [CountingVaultCrypto] wraps the real cipher to prove the single-chunk property the spec
 * demands: seeking to 3.2 MB in a 7-chunk file decrypts exactly **one** chunk, not the six
 * before it. Encoder ([VaultCrypto.encrypt]) and the seeking reader are independent code
 * paths, so a wrong offset map or nonce derivation would mismatch bytes or fail the tag.
 */
class VaultBlobReaderTest {
    private val key: SecretKey = VaultCrypto.newKey()
    private val crypto = VaultCrypto(key)
    private val p = VaultCrypto.CHUNK_BYTES

    private fun payload(size: Int): ByteArray = ByteArray(size).also { Random(42L).nextBytes(it) }

    private fun writeV2(plain: ByteArray): File {
        val f = File.createTempFile("vault-v2-", ".blob")
        f.deleteOnExit()
        FileOutputStream(f).use { out -> crypto.encrypt(ByteArrayInputStream(plain), out) }
        return f
    }

    /** Build a pre-APP-244 v1 blob: `IV(12) ‖ ciphertext+tag` as one GCM message. */
    private fun writeV1(plain: ByteArray): File {
        val f = File.createTempFile("vault-v1-", ".blob")
        f.deleteOnExit()
        val iv = ByteArray(12).also { Random(9L).nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        FileOutputStream(f).use { out ->
            out.write(iv)
            out.write(cipher.doFinal(plain))
        }
        return f
    }

    /** Seek then read exactly [len] bytes, looping over one-chunk-max reads. */
    private fun readRange(
        reader: VaultBlobReader,
        from: Long,
        len: Int,
    ): ByteArray {
        reader.seekTo(from)
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = reader.read(out, got, len - got)
            if (n == -1) break
            got += n
        }
        return out.copyOf(got)
    }

    @Test
    fun `content length is derived from file length with zero decrypt`() {
        for (size in intArrayOf(0, 1, p - 1, p, p + 1, 2 * p, (2.5 * p).toInt(), 7 * p - 3)) {
            val counting = CountingVaultCrypto(key)
            VaultBlobReader(writeV2(payload(size)), counting).use { r ->
                assertThat(r.contentLength).isEqualTo(size.toLong())
            }
            assertThat(counting.count).isEqualTo(0) // geometry needs no decryption
        }
    }

    @Test
    fun `arbitrary-offset seeks return byte-correct plaintext`() {
        val plain = payload((3.25 * p).toInt()) // ~1.7 MB, 4 chunks
        val reader = VaultBlobReader(writeV2(plain), crypto)
        reader.use {
            for (from in longArrayOf(0, 900_000, p.toLong(), p.toLong() + 1, 1_500_000, plain.size - 10L)) {
                val len = minOf(50_000L, plain.size - from).toInt()
                val expected = plain.copyOfRange(from.toInt(), from.toInt() + len)
                assertThat(readRange(reader, from, len)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `seek near end decrypts only the one needed chunk`() {
        val plain = payload(7 * p - 100) // 7 chunks
        val counting = CountingVaultCrypto(key)
        VaultBlobReader(writeV2(plain), counting).use { r ->
            val from = (6 * p).toLong() + 1000 // deep in the final chunk
            val expected = plain.copyOfRange(from.toInt(), from.toInt() + 20_000)
            assertThat(readRange(r, from, 20_000)).isEqualTo(expected)
        }
        assertThat(counting.count).isEqualTo(1) // NOT 7 — the spec's make-or-break proof
    }

    @Test
    fun `backward seek is correct and re-reads a single chunk`() {
        val plain = payload(5 * p)
        val counting = CountingVaultCrypto(key)
        VaultBlobReader(writeV2(plain), counting).use { r ->
            readRange(r, (4 * p).toLong(), 1000) // jump to chunk 4
            val before = counting.count
            val expected = plain.copyOfRange(100_000, 110_000)
            assertThat(readRange(r, 100_000, 10_000)).isEqualTo(expected) // back to chunk 0
            assertThat(counting.count - before).isEqualTo(1)
        }
    }

    @Test
    fun `full sequential read equals the whole plaintext`() {
        val plain = payload((3.5 * p).toInt())
        VaultBlobReader(writeV2(plain), crypto).use { r ->
            assertThat(readRange(r, 0, plain.size)).isEqualTo(plain)
        }
    }

    @Test
    fun `chunk-boundary seek is exact`() {
        val plain = payload(3 * p)
        VaultBlobReader(writeV2(plain), crypto).use { r ->
            val expected = plain.copyOfRange(p, p + 500)
            assertThat(readRange(r, p.toLong(), 500)).isEqualTo(expected)
        }
    }

    @Test
    fun `tampered chunk throws on the seek that touches it`() {
        val plain = payload(3 * p)
        val blob = writeV2(plain)
        // Flip a byte inside chunk 1's ciphertext (past header + all of sealed chunk 0).
        val bytes = blob.readBytes()
        val chunk1Start = VaultCrypto.V2_HEADER_BYTES + VaultCrypto.SEALED_CHUNK_BYTES
        bytes[chunk1Start + 42] = (bytes[chunk1Start + 42] + 1).toByte()
        blob.writeBytes(bytes)

        VaultBlobReader(blob, crypto).use { r ->
            var threw = false
            try {
                readRange(r, p.toLong(), 1000) // lands in the tampered chunk 1
            } catch (e: GeneralSecurityException) {
                threw = true
            }
            assertThat(threw).isTrue()
        }
    }

    @Test
    fun `v1 legacy blob plays forward and seeks by rewind`() {
        val plain = payload(300_000) // v1 blobs are small/rare
        VaultBlobReader(writeV1(plain), crypto).use { r ->
            assertThat(r.contentLength).isEqualTo(plain.size.toLong())
            // forward
            assertThat(readRange(r, 50_000, 10_000))
                .isEqualTo(plain.copyOfRange(50_000, 60_000))
            // backward seek forces a rewind-from-0; still byte-correct
            assertThat(readRange(r, 1_000, 5_000))
                .isEqualTo(plain.copyOfRange(1_000, 6_000))
        }
    }

    /** A [VaultCrypto] that tallies single-chunk decrypts, to prove seeks stay O(1). */
    private class CountingVaultCrypto(
        key: SecretKey,
    ) : VaultCrypto(key) {
        var count = 0
            private set

        override fun decryptChunk(
            noncePrefix: ByteArray,
            counter: Int,
            isFinal: Boolean,
            sealed: ByteArray,
        ): ByteArray {
            count++
            return super.decryptChunk(noncePrefix, counter, isFinal, sealed)
        }
    }
}
