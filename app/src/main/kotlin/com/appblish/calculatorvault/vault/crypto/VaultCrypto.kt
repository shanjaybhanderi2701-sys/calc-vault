package com.appblish.calculatorvault.vault.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM stream encryption for vault blobs. Each imported file is encrypted with
 * a fresh 12-byte IV, which is prepended to the ciphertext so decryption is
 * self-describing. The 256-bit data key is generated once and kept in the platform
 * keystore-backed secure store (see the vault storage seam) — it never touches disk in
 * cleartext.
 *
 * Pure JVM crypto (javax.crypto) so the round-trip is unit-testable off-device.
 */
class VaultCrypto(
    private val key: SecretKey,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Encrypt [source] into [sink], prepending the IV. Streams in [BUFFER] chunks so a
     * large video never fully materializes in memory. Caller owns closing the streams.
     */
    fun encrypt(
        source: InputStream,
        sink: OutputStream,
    ) {
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        sink.write(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        CipherOutputStream(sink, cipher).use { cipherOut ->
            source.copyTo(cipherOut, BUFFER)
        }
    }

    /**
     * Decrypt a blob produced by [encrypt] into [sink]. Reads the leading IV, then streams
     * the remaining ciphertext through a [CipherInputStream] in [BUFFER] chunks — the wire
     * format ([IV_LENGTH]-byte IV ‖ ciphertext+tag) is unchanged, only the whole-blob
     * `doFinal` load was replaced, so every pre-existing blob on disk still decrypts and a
     * multi-GB video never materializes as one ByteArray (bulk-op hardening, spec §11).
     *
     * Throws [java.security.GeneralSecurityException] if the GCM tag fails (tamper / wrong
     * key), so a corrupted blob is never silently served. [CipherInputStream] wraps the
     * AEADBadTagException in an IOException on the final read; it is unwrapped here so
     * callers keep the same wrong-passphrase contract as the pre-streaming one-shot cipher
     * (decoy isolation relies on it). NOTE: because GCM only authenticates at the end of
     * the stream, [sink] may have received partial unauthenticated plaintext before the
     * throw; callers must discard their output on any exception (the repository deletes
     * partial files / rolls back pending MediaStore rows).
     */
    fun decrypt(
        source: InputStream,
        sink: OutputStream,
    ) {
        val iv = ByteArray(IV_LENGTH)
        var read = 0
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
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val BUFFER = 64 * 1024
        const val KEY_BITS = 256

        /** Generate a fresh 256-bit AES key for a first-run install. */
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()
    }
}
