package com.appblish.calculatorvault.vault.player

import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.CipherInputStream
import kotlin.math.min

/**
 * Pure-JVM random-access reader over an AES-256-GCM vault blob — the seek engine behind
 * [EncryptedVaultDataSource] (APP-347 / W1-DESIGN §2.1, §3). It has **no Android or Media3
 * dependency**, so the make-or-break arbitrary-offset-seek behaviour is unit-testable
 * off-device exactly as the Gate-B spike proved it (`VaultBlobReaderTest`).
 *
 * It serves **decrypted plaintext byte-for-byte identical to the original file** while
 * decrypting only the one 512 KiB chunk each [seekTo]/[read] needs — never the whole file,
 * and **never a plaintext temp file on disk** (spec §1.1). Peak working set is one sealed
 * chunk + one plaintext chunk (~1 MB), independent of video size (no OOM on 1 GB+ files).
 *
 * Two on-disk shapes are handled:
 * - **v2 (chunked, [VaultCrypto.MAGIC]-prefixed):** truly seekable. `fileOffset(i) =
 *   V2_HEADER_BYTES + i·S`, so a seek maps to a single-chunk decrypt.
 * - **v1 (legacy single-GCM-message):** cryptographically un-seekable, so a backward seek
 *   re-opens the cipher stream from 0 and skips forward — still zero-plaintext-on-disk,
 *   bounded memory (spec §5, confirmed acceptable; v1 blobs predate the video vault).
 *
 * Every served chunk is GCM-verified: tamper / wrong key / truncation throw a
 * [java.security.GeneralSecurityException] *before* any byte is delivered. One instance per
 * open; not thread-safe. [close] zero-fills the plaintext buffer.
 */
class VaultBlobReader(
    private val blob: File,
    private val crypto: VaultCrypto,
) : Closeable {
    private val impl: Impl = if (isV2(blob)) V2(blob, crypto) else V1(blob, crypto)

    /** Total plaintext length, derived without decrypting anything (v2) / from length (v1). */
    val contentLength: Long get() = impl.contentLength

    /** Position the reader at plaintext byte [position]; the next [read] returns from there. */
    fun seekTo(position: Long) {
        require(position in 0..contentLength) { "seek $position out of 0..$contentLength" }
        impl.seekTo(position)
    }

    /**
     * Fill [buffer] from `offset` with up to [length] decrypted bytes from the current
     * position. Returns the count, or -1 at end of stream. Never crosses more than one
     * chunk boundary per call (ExoPlayer loops as needed).
     */
    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = impl.read(buffer, offset, length)

    override fun close() = impl.close()

    // --- implementations ----------------------------------------------------------------

    private interface Impl {
        val contentLength: Long

        fun seekTo(position: Long)

        fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int

        fun close()
    }

    private class V2(
        blob: File,
        private val crypto: VaultCrypto,
    ) : Impl {
        private val file = RandomAccessFile(blob, "r")
        private val noncePrefix = ByteArray(VaultCrypto.NONCE_PREFIX_LENGTH)
        private val payload: Long = file.length() - VaultCrypto.V2_HEADER_BYTES
        private val finalIndex: Int
        override val contentLength: Long

        private var plain: ByteArray = EMPTY
        private var plainPos = 0
        private var chunkIdx = -1

        init {
            file.seek(MAGIC_LEN)
            file.readFully(noncePrefix)
            val s = VaultCrypto.SEALED_CHUNK_BYTES.toLong()
            when {
                payload <= 0L -> {
                    finalIndex = 0
                    contentLength = 0L
                }
                payload % s == 0L -> {
                    // Exact multiple of S ⇒ a full-size final chunk, no trailing chunk.
                    finalIndex = (payload / s).toInt() - 1
                    contentLength = finalIndex.toLong() * VaultCrypto.CHUNK_BYTES + VaultCrypto.CHUNK_BYTES
                }
                else -> {
                    finalIndex = (payload / s).toInt()
                    val finalPlainLen = (payload % s).toInt() - VaultCrypto.TAG_BYTES
                    if (finalPlainLen < 0) throw IOException("Corrupt vault blob geometry")
                    contentLength = finalIndex.toLong() * VaultCrypto.CHUNK_BYTES + finalPlainLen
                }
            }
        }

        override fun seekTo(position: Long) {
            if (position >= contentLength) {
                // Position at EOF without decrypting a phantom chunk past the end.
                loadChunk(finalIndex)
                plainPos = plain.size
                return
            }
            loadChunk((position / VaultCrypto.CHUNK_BYTES).toInt())
            plainPos = (position % VaultCrypto.CHUNK_BYTES).toInt()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            if (plainPos >= plain.size) {
                if (chunkIdx >= finalIndex) return -1
                loadChunk(chunkIdx + 1)
                plainPos = 0 // start of the freshly-decrypted next chunk
            }
            val n = min(length, plain.size - plainPos)
            System.arraycopy(plain, plainPos, buffer, offset, n)
            plainPos += n
            return n
        }

        /** Decrypt exactly chunk [idx] into [plain]; no-op if already loaded (single-chunk cost). */
        private fun loadChunk(idx: Int) {
            if (idx == chunkIdx) return
            val s = VaultCrypto.SEALED_CHUNK_BYTES.toLong()
            val fileOffset = VaultCrypto.V2_HEADER_BYTES + idx * s
            val isFinal = idx == finalIndex
            val sealedLen = if (isFinal) (payload - idx * s).toInt() else VaultCrypto.SEALED_CHUNK_BYTES
            val sealed = ByteArray(sealedLen)
            file.seek(fileOffset)
            file.readFully(sealed)
            plain.fill(0)
            plain = crypto.decryptChunk(noncePrefix, idx, isFinal, sealed)
            chunkIdx = idx
            sealed.fill(0)
        }

        override fun close() {
            plain.fill(0)
            plain = EMPTY
            runCatching { file.close() }
        }
    }

    private class V1(
        private val blob: File,
        private val crypto: VaultCrypto,
    ) : Impl {
        override val contentLength: Long =
            (blob.length() - VaultCrypto.IV_LENGTH - VaultCrypto.TAG_BYTES).coerceAtLeast(0)
        private var stream: InputStream? = null
        private var streamPos = 0L

        override fun seekTo(position: Long) {
            if (stream == null || position < streamPos) reopen()
            skipFully(position - streamPos)
            streamPos = position
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val s = stream ?: reopen().let { stream!! }
            if (length == 0) return 0
            val n = s.read(buffer, offset, length)
            if (n > 0) streamPos += n
            return n
        }

        private fun reopen() {
            close()
            val raw = FileInputStream(blob)
            val iv = ByteArray(VaultCrypto.IV_LENGTH)
            var read = 0
            while (read < iv.size) {
                val r = raw.read(iv, read, iv.size - read)
                if (r < 0) throw EOFException("Truncated v1 vault blob: missing IV")
                read += r
            }
            stream = CipherInputStream(raw, crypto.legacyDecryptCipher(iv))
            streamPos = 0L
        }

        private fun skipFully(count: Long) {
            var remaining = count
            val scratch = ByteArray(64 * 1024)
            val s = stream ?: return
            while (remaining > 0) {
                val n = s.read(scratch, 0, min(remaining, scratch.size.toLong()).toInt())
                if (n < 0) break
                remaining -= n
            }
            scratch.fill(0)
        }

        override fun close() {
            runCatching { stream?.close() }
            stream = null
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val MAGIC_LEN = 8L

        /** True when [blob] starts with the v2 [VaultCrypto.MAGIC]; else a legacy v1 blob. */
        fun isV2(blob: File): Boolean {
            val magic = VaultCrypto.MAGIC
            if (blob.length() < magic.size) return false
            val head = ByteArray(magic.size)
            RandomAccessFile(blob, "r").use { it.readFully(head) }
            return head.contentEquals(magic)
        }
    }
}
