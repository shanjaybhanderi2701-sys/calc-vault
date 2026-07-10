package com.appblish.calculatorvault.vault.crypto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption for vault blobs, in bounded memory.
 *
 * **Why chunked (APP-244).** Android's Conscrypt provider implements GCM as a one-shot
 * AEAD: `Cipher.update()` only *buffers*, and the whole message materializes inside the
 * cipher at `doFinal` — so the previous single-GCM-pass "stream" still held an entire
 * video in memory on device and OOM-crashed on large hides. The fix is a chunked format:
 * each ≤[CHUNK_BYTES] plaintext chunk is sealed as its own GCM message, so peak memory is
 * one chunk regardless of file size. The AES-256-GCM key, tag length, and authenticity
 * guarantees are unchanged — only the framing is new.
 *
 * **Chunked wire format (v2, written by [encrypt]):**
 * `MAGIC(8) ‖ noncePrefix(7) ‖ chunk₀ ‖ chunk₁ ‖ …` where `chunkᵢ = GCM(key, nonceᵢ,
 * plainᵢ)` (ciphertext + 16-byte tag). `nonceᵢ = noncePrefix ‖ i (4-byte BE) ‖ finalFlag
 * (1 byte)`. The counter in the nonce makes chunk reordering/duplication fail the tag;
 * the final-flag byte makes truncation at a chunk boundary fail the tag (the last kept
 * chunk was sealed with `final=0` but is decrypted as `final=1`). Same construction as
 * Tink's StreamingAead segment nonces.
 *
 * **Legacy format (v1, still read by [decrypt]):** `IV(12) ‖ ciphertext+tag` as a single
 * GCM message — every blob/index written before APP-244 opens unchanged. [decrypt] sniffs
 * the 8-byte magic to pick the path (a v1 IV colliding with the magic has probability
 * 2⁻⁶⁴; accepted).
 *
 * Throws [GeneralSecurityException] if any GCM tag fails (tamper / wrong key), so a
 * corrupted blob is never silently served. NOTE: [sink] may have received earlier,
 * individually-authenticated chunks before a later chunk throws; callers must discard
 * their output on any exception (the repository deletes partial files / rolls back
 * pending MediaStore rows) — same contract as before.
 *
 * Pure JVM crypto (javax.crypto) so the round-trip is unit-testable off-device.
 */
open class VaultCrypto(
    private val key: SecretKey,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Encrypt [source] into [sink] using the chunked v2 format. Peak memory is one
     * [CHUNK_BYTES] chunk (+ its ciphertext), never the whole file. Caller owns closing
     * the streams.
     */
    fun encrypt(
        source: InputStream,
        sink: OutputStream,
    ) {
        sink.write(MAGIC)
        val noncePrefix = ByteArray(NONCE_PREFIX_LENGTH).also(random::nextBytes)
        sink.write(noncePrefix)
        var counter = 0
        var current = readUpTo(source, CHUNK_BYTES)
        while (true) {
            // A short read means EOF was hit filling `current`, so it is the final chunk;
            // a full chunk needs one look-ahead read to learn whether more data follows.
            val next = if (current.size < CHUNK_BYTES) EMPTY else readUpTo(source, CHUNK_BYTES)
            val isFinal = next.isEmpty()
            sink.write(sealChunk(noncePrefix, counter, isFinal, current))
            if (isFinal) return
            check(counter < Int.MAX_VALUE) { "Vault blob too large" }
            counter++
            current = next
        }
    }

    /**
     * Decrypt a blob produced by [encrypt] (chunked v2) or by the pre-APP-244 single-pass
     * cipher (v1) into [sink], in bounded memory for v2. Throws [GeneralSecurityException]
     * on any tag failure, truncation, reordering, or wrong key.
     */
    fun decrypt(
        source: InputStream,
        sink: OutputStream,
    ) {
        val head = readUpTo(source, MAGIC.size)
        if (head.size == MAGIC.size && head.contentEquals(MAGIC)) {
            decryptChunked(source, sink)
        } else {
            decryptLegacy(head, source, sink)
        }
    }

    // --- v2 chunked ------------------------------------------------------------------

    private fun decryptChunked(
        source: InputStream,
        sink: OutputStream,
    ) {
        val noncePrefix = readUpTo(source, NONCE_PREFIX_LENGTH)
        if (noncePrefix.size < NONCE_PREFIX_LENGTH) throw EOFException("Truncated vault blob: missing nonce prefix")
        val sealedChunkBytes = CHUNK_BYTES + TAG_BITS / 8
        var counter = 0
        var current = readUpTo(source, sealedChunkBytes)
        while (true) {
            val next = if (current.size < sealedChunkBytes) EMPTY else readUpTo(source, sealedChunkBytes)
            val isFinal = next.isEmpty()
            // Throws AEADBadTagException (a GeneralSecurityException) on tamper, on a
            // reordered counter, and on boundary truncation (final-flag mismatch).
            sink.write(openChunk(noncePrefix, counter, isFinal, current))
            if (isFinal) return
            counter++
            current = next
        }
    }

    private fun sealChunk(
        noncePrefix: ByteArray,
        counter: Int,
        isFinal: Boolean,
        plain: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkNonce(noncePrefix, counter, isFinal)))
        return cipher.doFinal(plain)
    }

    private fun openChunk(
        noncePrefix: ByteArray,
        counter: Int,
        isFinal: Boolean,
        sealed: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkNonce(noncePrefix, counter, isFinal)))
        return cipher.doFinal(sealed)
    }

    /**
     * Decrypt a single v2 chunk in isolation — the one crypto primitive the seekable
     * [com.appblish.calculatorvault.vault.player.EncryptedVaultDataSource] shares with
     * the streaming [decrypt] path (APP-347 §8.1: one code path, no duplicated crypto).
     *
     * [sealed] must be exactly the on-disk `sealedᵢ` bytes for chunk [counter] (ciphertext
     * + 16-byte GCM tag). [isFinal] must match how the chunk was sealed — a mismatch fails
     * the tag (that is the format's truncation defense, preserved on the random-access
     * path). Throws [GeneralSecurityException] on tamper / wrong key / boundary truncation.
     * The nonce derivation is byte-identical to [encrypt]'s, so a seek reader driven by
     * this method serves plaintext indistinguishable from the original file.
     */
    open fun decryptChunk(
        noncePrefix: ByteArray,
        counter: Int,
        isFinal: Boolean,
        sealed: ByteArray,
    ): ByteArray = openChunk(noncePrefix, counter, isFinal, sealed)

    /**
     * Decrypt a legacy v1 blob body (post-IV) with a caller-supplied [iv], for the
     * forward-only v1 fallback DataSource (§5). Returns a [Cipher] initialised for
     * streaming; the caller wraps the ciphertext stream in a `CipherInputStream`. Kept
     * here so v1 IV/transformation handling is never duplicated outside this class.
     */
    fun legacyDecryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }

    /** 12-byte per-chunk nonce: `prefix(7) ‖ counter(4, big-endian) ‖ finalFlag(1)`. */
    private fun chunkNonce(
        noncePrefix: ByteArray,
        counter: Int,
        isFinal: Boolean,
    ): ByteArray =
        ByteArray(IV_LENGTH).also { nonce ->
            noncePrefix.copyInto(nonce)
            nonce[7] = (counter ushr 24).toByte()
            nonce[8] = (counter ushr 16).toByte()
            nonce[9] = (counter ushr 8).toByte()
            nonce[10] = counter.toByte()
            nonce[11] = if (isFinal) 1 else 0
        }

    /** Read up to [limit] bytes, looping until the buffer is full or EOF; may be short. */
    private fun readUpTo(
        source: InputStream,
        limit: Int,
    ): ByteArray {
        val buf = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = source.read(buf, read, limit - read)
            if (n < 0) break
            read += n
        }
        return if (read == limit) buf else buf.copyOf(read)
    }

    // --- v1 legacy (read-only) ---------------------------------------------------------

    /**
     * Decrypt a pre-APP-244 blob: `IV(12) ‖ ciphertext+tag` as one GCM message. [head] is
     * the already-consumed magic probe (the IV's first bytes). [CipherInputStream] wraps
     * the AEADBadTagException in an IOException on the final read; it is unwrapped here so
     * callers keep the same wrong-passphrase contract (decoy isolation relies on it).
     */
    private fun decryptLegacy(
        head: ByteArray,
        source: InputStream,
        sink: OutputStream,
    ) {
        val iv = ByteArray(IV_LENGTH)
        head.copyInto(iv)
        var read = head.size
        while (read < IV_LENGTH) {
            val n = source.read(iv, read, IV_LENGTH - read)
            require(n >= 0) { "Truncated vault blob: missing IV" }
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        try {
            CipherInputStream(source, cipher).use { cipherIn ->
                cipherIn.copyTo(sink, BUFFER)
            }
        } catch (e: IOException) {
            throw (e.cause as? GeneralSecurityException) ?: e
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        internal const val IV_LENGTH = 12
        internal const val NONCE_PREFIX_LENGTH = 7
        private const val TAG_BITS = 128
        private const val BUFFER = 64 * 1024
        private val EMPTY = ByteArray(0)
        const val KEY_BITS = 256

        /** GCM tag bytes appended to every sealed chunk (128-bit tag). */
        internal const val TAG_BYTES = TAG_BITS / 8

        /** v2 chunked-format marker ("CVCHUNK1"). A v1 blob starts with a random IV. */
        internal val MAGIC = byteArrayOf(0x43, 0x56, 0x43, 0x48, 0x55, 0x4E, 0x4B, 0x31)

        /**
         * Fixed on-disk header of a v2 blob: `MAGIC(8) ‖ noncePrefix(7)`. The random-access
         * reader computes chunk file offsets from this ([EncryptedVaultDataSource] §2.1).
         */
        internal const val V2_HEADER_BYTES = 8 + NONCE_PREFIX_LENGTH

        /** Plaintext bytes per sealed chunk — the peak per-file memory of a crypto pass. */
        internal const val CHUNK_BYTES = 512 * 1024

        /** On-disk size of a non-final sealed chunk: plaintext + GCM tag. */
        internal const val SEALED_CHUNK_BYTES = CHUNK_BYTES + TAG_BYTES

        /** Generate a fresh 256-bit AES key for a first-run install. */
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()
    }
}
