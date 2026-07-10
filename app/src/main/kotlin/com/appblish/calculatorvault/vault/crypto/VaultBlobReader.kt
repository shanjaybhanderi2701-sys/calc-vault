package com.appblish.calculatorvault.vault.crypto

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.CipherInputStream

/**
 * A random-access, decrypting view over a single vault blob, for **streaming** media
 * playback (APP-347 / spec §1.2). Decrypts on demand into small in-RAM buffers and
 * **never writes plaintext to disk** (§1.1) — it replaces the Phase-1 whole-file
 * `decryptToFile` temp-file path for video/audio.
 *
 * Two implementations, chosen by sniffing the v2 magic in [openBlobReader]:
 * - [V2ChunkedBlobReader] — the shipping chunked AES-256-GCM format. Arbitrary-offset
 *   [seekTo] decrypts exactly **one** 512 KiB chunk (O(1) file-offset map, [isSeekable]).
 * - [V1LegacyBlobReader] — pre-APP-244 single-GCM blobs. Forward-only; a backward seek
 *   reopens from 0 and skips forward (still zero plaintext on disk, just not fast).
 *
 * All decrypt happens on the caller's thread — the media DataSource calls these from
 * ExoPlayer's loader thread, never the UI thread (§1.3). Every served chunk is GCM-verified,
 * so tamper/wrong-key/truncation throws before any byte is delivered.
 */
interface DecryptingBlobReader : Closeable {
    /**
     * Total plaintext length in bytes, or a negative value if unknown (legacy v1, where the
     * length can't be derived without decrypting the whole message). For v2 this is computed
     * from the file length alone — **zero decryption** — so the seekbar shows total time
     * without touching the ciphertext.
     */
    val contentLength: Long

    /** True when [seekTo] is O(1) (v2). False ⇒ backward seeks reopen-and-skip (v1). */
    val isSeekable: Boolean

    /** Position the reader at plaintext byte [position]; the next [read] returns from there. */
    fun seekTo(position: Long)

    /**
     * Copy up to [length] decrypted plaintext bytes into [target] starting at [offset].
     * Returns the number of bytes copied (>0), or -1 at end of input. May return fewer than
     * [length] (it never crosses a chunk boundary in one call).
     */
    fun read(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

/**
 * Open a [DecryptingBlobReader] over [file], sniffing the 8-byte v2 magic to pick the
 * seekable chunked reader or the forward-only legacy reader — the same magic sniff
 * [VaultCrypto.decrypt] uses. Never writes plaintext to disk.
 */
fun VaultCrypto.openBlobReader(file: File): DecryptingBlobReader {
    val head = ByteArray(VaultCrypto.MAGIC.size)
    val isChunked =
        RandomAccessFile(file, "r").use { raf ->
            val read = raf.read(head)
            read == head.size && isChunkedMagic(head)
        }
    return if (isChunked) V2ChunkedBlobReader(this, file) else V1LegacyBlobReader(this, file)
}

/**
 * Random-access reader over a v2 chunked blob. The whole trick (W1-DESIGN §2.1): every
 * non-final sealed chunk is exactly [VaultCrypto.SEALED_CHUNK_LENGTH] bytes on disk, so
 * chunk *i* lives at a **computable** offset and a seek decrypts just that one chunk.
 */
internal class V2ChunkedBlobReader(
    private val crypto: VaultCrypto,
    file: File,
) : DecryptingBlobReader {
    private val raf = RandomAccessFile(file, "r")
    private val fileLength = raf.length()
    private val noncePrefix = ByteArray(VaultCrypto.NONCE_PREFIX_LENGTH)

    /** Index of the last chunk (sealed with `final=1`); needed to pick the nonce final-flag. */
    private val finalIndex: Int
    private val finalPlainLen: Int
    override val contentLength: Long
    override val isSeekable: Boolean = true

    // Reusable buffer for a full (non-final) sealed chunk; the final chunk gets its own
    // exact-sized array (GCM doFinal authenticates the whole array, so it must be tight).
    private val sealedBuf = ByteArray(VaultCrypto.SEALED_CHUNK_LENGTH)
    private var plain: ByteArray = EMPTY
    private var plainIndex = -1
    private var plainPos = 0
    private var eof = false

    init {
        val payload = fileLength - VaultCrypto.HEADER_LENGTH
        require(payload >= 0) { "Truncated vault blob: shorter than header" }
        raf.seek(VaultCrypto.MAGIC.size.toLong())
        raf.readFully(noncePrefix)
        val s = VaultCrypto.SEALED_CHUNK_LENGTH.toLong()
        val p = VaultCrypto.CHUNK_BYTES
        if (payload % s == 0L) {
            // Exact multiple ⇒ the final chunk is full-size; there is no trailing short chunk.
            finalIndex = (payload / s).toInt() - 1
            finalPlainLen = p
        } else {
            finalIndex = (payload / s).toInt()
            finalPlainLen = (payload % s).toInt() - VaultCrypto.TAG_LENGTH
        }
        contentLength = finalIndex.toLong() * p + finalPlainLen
    }

    override fun seekTo(position: Long) {
        if (position >= contentLength) {
            eof = true
            plain = EMPTY
            plainPos = 0
            return
        }
        eof = false
        loadChunk((position / VaultCrypto.CHUNK_BYTES).toInt())
        plainPos = (position % VaultCrypto.CHUNK_BYTES).toInt()
    }

    override fun read(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (eof || length == 0) return if (eof) -1 else 0
        // Advance across drained chunks (a non-final chunk is never empty, so this loops
        // at most once in practice; the guard also covers an empty final chunk).
        while (plainPos >= plain.size) {
            if (plainIndex < 0 || plainIndex >= finalIndex) {
                eof = true
                return -1
            }
            loadChunk(plainIndex + 1)
        }
        val n = minOf(length, plain.size - plainPos)
        System.arraycopy(plain, plainPos, target, offset, n)
        plainPos += n
        return n
    }

    private fun loadChunk(index: Int) {
        val isFinal = index == finalIndex
        val fileOffset = VaultCrypto.HEADER_LENGTH.toLong() + index.toLong() * VaultCrypto.SEALED_CHUNK_LENGTH
        raf.seek(fileOffset)
        val sealed: ByteArray =
            if (isFinal) {
                ByteArray((fileLength - fileOffset).toInt()).also { raf.readFully(it) }
            } else {
                sealedBuf.also { raf.readFully(it) }
            }
        // Throws (AEADBadTagException) on tamper / wrong key / a final-flag mismatch —
        // authenticity is enforced on the seek path exactly as on the sequential path.
        plain = crypto.decryptChunk(noncePrefix, index, isFinal, sealed)
        plainIndex = index
        plainPos = 0
    }

    override fun close() {
        plain.fill(0)
        sealedBuf.fill(0)
        raf.close()
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Forward-only reader over a legacy v1 blob (`IV(12) ‖ ciphertext+tag`, one GCM message).
 * Random access is cryptographically impossible on a single-message GCM, so a backward or
 * beyond-current seek reopens the stream from 0 and skips forward (W1-DESIGN §5). Still
 * **zero plaintext on disk** — we never fall back to a temp file. Length is unknown without
 * decrypting the whole thing, so [contentLength] is negative (the DataSource maps this to
 * `C.LENGTH_UNSET`).
 */
internal class V1LegacyBlobReader(
    private val crypto: VaultCrypto,
    private val file: File,
) : DecryptingBlobReader {
    override val contentLength: Long = -1L
    override val isSeekable: Boolean = false

    private var stream: InputStream? = null
    private var streamPos = 0L
    private var eof = false

    override fun seekTo(position: Long) {
        val current = stream
        if (current != null && position >= streamPos) {
            skipForward(position - streamPos)
        } else {
            reopenFromStart()
            skipForward(position)
        }
    }

    override fun read(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (eof) return -1
        val source = stream ?: reopenFromStart().let { stream!! }
        val n = source.read(target, offset, length)
        if (n < 0) {
            eof = true
            return -1
        }
        streamPos += n
        return n
    }

    private fun reopenFromStart() {
        stream?.close()
        eof = false
        streamPos = 0
        val input = file.inputStream().buffered()
        val iv = ByteArray(VaultCrypto.IV_LENGTH)
        var read = 0
        while (read < iv.size) {
            val r = input.read(iv, read, iv.size - read)
            require(r >= 0) { "Truncated vault blob: missing IV" }
            read += r
        }
        stream = CipherInputStream(input, crypto.newLegacyDecryptCipher(iv))
    }

    private fun skipForward(count: Long) {
        if (count <= 0) return
        val source = stream ?: return
        val scratch = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val n = source.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) {
                eof = true
                return
            }
            remaining -= n
            streamPos += n
        }
    }

    override fun close() {
        stream?.close()
        stream = null
    }
}
