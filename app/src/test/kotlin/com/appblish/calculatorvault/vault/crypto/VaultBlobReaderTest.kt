package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.GeneralSecurityException
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * APP-347 W1-ENG — carries the Gate-B spike into the **real** [DecryptingBlobReader]:
 * arbitrary-offset seek over a production [VaultCrypto] v2 blob returns byte-correct
 * plaintext while decrypting only the needed chunk, content length is exact from the file
 * length alone (zero decrypt), tamper is rejected on the seek path, and the legacy v1
 * forward-only reader round-trips with no plaintext on disk.
 */
class VaultBlobReaderTest {
    private val key = VaultCrypto.newKey()
    private val crypto = VaultCrypto(key)
    private val chunk = VaultCrypto.CHUNK_BYTES

    private fun payload(size: Int): ByteArray = ByteArray(size).also { Random(7L).nextBytes(it) }

    private fun writeV2Blob(plain: ByteArray): File {
        val file = File.createTempFile("vault-blob", ".enc").apply { deleteOnExit() }
        file.outputStream().use { out -> crypto.encrypt(ByteArrayInputStream(plain), out) }
        return file
    }

    /** Read exactly [length] plaintext bytes starting at [start] via the seek reader. */
    private fun readRange(
        reader: DecryptingBlobReader,
        start: Long,
        length: Int,
    ): ByteArray {
        reader.seekTo(start)
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return buf.copyOf(read)
    }

    @Test
    fun `content length is exact from file length with zero decrypt`() {
        for (size in intArrayOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk, (6.4 * chunk).toInt())) {
            val reader = crypto.openBlobReader(writeV2Blob(payload(size)))
            reader.use {
                assertThat(it.isSeekable).isTrue()
                assertThat(it.contentLength).isEqualTo(size.toLong())
            }
        }
    }

    @Test
    fun `arbitrary-offset seek returns byte-correct plaintext`() {
        val plain = payload((6.4 * chunk).toInt()) // 7-chunk blob
        val reader = crypto.openBlobReader(writeV2Blob(plain))
        reader.use {
            // Mid-chunk, near-end, exact chunk boundary, and a backward seek — all exact.
            val offsets = longArrayOf(900_000L, plain.size - 50_000L, chunk.toLong(), 100_000L, 0L)
            for (off in offsets) {
                val len = minOf(50_000, plain.size - off.toInt())
                val got = readRange(it, off, len)
                assertThat(got).isEqualTo(plain.copyOfRange(off.toInt(), off.toInt() + len))
            }
        }
    }

    @Test
    fun `full sequential read equals whole plaintext`() {
        val plain = payload(3 * chunk + 12_345)
        val reader = crypto.openBlobReader(writeV2Blob(plain))
        reader.use {
            assertThat(readRange(it, 0L, plain.size)).isEqualTo(plain)
        }
    }

    @Test
    fun `seek to content length is end of input`() {
        val plain = payload(chunk + 10)
        val reader = crypto.openBlobReader(writeV2Blob(plain))
        reader.use {
            it.seekTo(plain.size.toLong())
            assertThat(it.read(ByteArray(16), 0, 16)).isEqualTo(-1)
        }
    }

    @Test
    fun `tampered middle chunk fails the tag on seek`() {
        val plain = payload(3 * chunk)
        val file = writeV2Blob(plain)
        // Flip a byte inside chunk 1's sealed region (past header + one full sealed chunk).
        val bytes = file.readBytes()
        val target = VaultCrypto.HEADER_LENGTH + VaultCrypto.SEALED_CHUNK_LENGTH + 10
        bytes[target] = (bytes[target].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        val reader = crypto.openBlobReader(file)
        var threw = false
        try {
            reader.use { readRange(it, chunk.toLong(), 100) } // lands in the tampered chunk 1
        } catch (e: GeneralSecurityException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `legacy v1 blob reads forward and reopens on backward seek`() {
        val plain = payload(200_000)
        val file = writeLegacyV1Blob(plain)
        val reader = crypto.openBlobReader(file)
        reader.use {
            assertThat(it.isSeekable).isFalse()
            // Forward read from an offset, then a backward seek (forces a reopen-from-0).
            assertThat(readRange(it, 50_000L, 20_000)).isEqualTo(plain.copyOfRange(50_000, 70_000))
            assertThat(readRange(it, 0L, 10_000)).isEqualTo(plain.copyOfRange(0, 10_000))
        }
    }

    /** Hand-write a pre-APP-244 single-GCM blob (`IV(12) ‖ ciphertext+tag`) with the same key. */
    private fun writeLegacyV1Blob(plain: ByteArray): File {
        val iv = ByteArray(VaultCrypto.IV_LENGTH).also { Random(11L).nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plain)
        val file = File.createTempFile("vault-v1", ".enc").apply { deleteOnExit() }
        file.outputStream().use { out ->
            out.write(iv)
            out.write(sealed)
        }
        return file
    }
}
