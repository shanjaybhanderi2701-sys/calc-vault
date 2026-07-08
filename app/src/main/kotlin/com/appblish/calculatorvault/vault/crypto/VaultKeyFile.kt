package com.appblish.calculatorvault.vault.crypto

import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The vault's **PIN-recoverable** data key, persisted in the hidden public `.CalcVault/`
 * folder so it survives app uninstall alongside the blobs.
 *
 * Phase 2's original [VaultKeyStore] kept the data key (DEK) in EncryptedSharedPreferences
 * under an AndroidKeyStore master key — which the OS **wipes on uninstall**. That made the
 * survive-uninstall directive impossible: the blobs could live in public storage, but the
 * key to decrypt them could not. This file fixes that.
 *
 * Design (envelope encryption):
 * 1. A random 256-bit **DEK** encrypts the blobs and index (via [VaultCrypto]).
 * 2. A **KEK** is derived from the entry passphrase (the PIN) with PBKDF2-HMAC-SHA256 over
 *    a random salt.
 * 3. The DEK is wrapped (AES-256-GCM) under the KEK and written to `.vaultkey` with the
 *    salt + IV. The passphrase and the raw DEK never touch disk.
 *
 * On reinstall the file is still on shared storage, so re-entering the same PIN re-derives
 * the KEK, unwraps the DEK, and the vault is readable again. A wrong PIN fails the GCM tag,
 * surfaced as [WrongPassphraseException].
 *
 * PBKDF2 is implemented on `Mac("HmacSHA256")` (as in `auth.PinHasher`) rather than
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")`, which is only guaranteed from API 26 while
 * the app ships `minSdk 24`. Pure JVM → the envelope round-trip is unit-testable off-device.
 */
class VaultKeyFile(
    private val file: File,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Thrown by [unlock] when the passphrase does not decrypt the stored key. */
    class WrongPassphraseException : GeneralSecurityException("Wrong vault passphrase")

    /** True if a wrapped key already exists (vault was set up, possibly before a reinstall). */
    fun exists(): Boolean = file.exists()

    /**
     * Unwrap the DEK with [passphrase], or create+persist a fresh DEK on first setup.
     * Idempotent for a given passphrase: the same PIN always yields the same DEK.
     */
    fun unlockOrCreate(passphrase: String): SecretKey = if (file.exists()) unlock(passphrase) else create(passphrase)

    /** Unwrap the DEK for an existing key file. Throws [WrongPassphraseException] on a bad PIN. */
    fun unlock(passphrase: String): SecretKey {
        // Format: version:iterations:saltHex:ivHex:wrappedHex (single line, pure ASCII).
        val parts = file.readText().trim().split(SEPARATOR)
        require(parts.size == 5) { "Malformed vault key file" }
        val iterations = parts[1].toInt()
        val salt = decode(parts[2])
        val iv = decode(parts[3])
        val wrapped = decode(parts[4])
        val kek = deriveKek(passphrase, salt, iterations)
        val raw =
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
                cipher.doFinal(wrapped)
            } catch (e: GeneralSecurityException) {
                throw WrongPassphraseException()
            }
        return SecretKeySpec(raw, "AES")
    }

    /**
     * Envelope re-key for a PIN change (APP-245): unwrap the DEK with [oldPassphrase],
     * re-wrap it under a KEK derived from [newPassphrase] with a **fresh salt + IV**, and
     * atomically replace the key file (temp write + rename, so a crash mid-write leaves the
     * old envelope intact). The DEK itself never changes — every blob and the encrypted
     * index stay readable; only the wrapping moves to the new PIN. Afterwards the old PIN
     * fails the GCM tag exactly like any wrong passphrase.
     *
     * Throws [WrongPassphraseException] — with the file untouched — when [oldPassphrase]
     * does not unwrap the stored key, and [IllegalStateException] when there is no key file
     * (callers gate on [exists]; with nothing wrapped there is nothing to re-key).
     */
    fun rewrap(
        oldPassphrase: String,
        newPassphrase: String,
    ) {
        check(file.exists()) { "No vault key file to re-wrap" }
        val dek = unlock(oldPassphrase)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(serializeWrapped(dek, newPassphrase))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Could not atomically replace the vault key file")
        }
    }

    /** Generate a fresh DEK, wrap it under a KEK derived from [passphrase], and persist. */
    private fun create(passphrase: String): SecretKey {
        val dek = VaultCrypto.newKey()
        file.parentFile?.mkdirs()
        file.writeText(serializeWrapped(dek, passphrase))
        return dek
    }

    /** Wrap [dek] under a fresh-salt KEK from [passphrase] and serialize to the file format. */
    private fun serializeWrapped(
        dek: SecretKey,
        passphrase: String,
    ): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val kek = deriveKek(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
        val wrapped = cipher.doFinal(dek.encoded)
        return listOf(VERSION.toString(), ITERATIONS.toString(), encode(salt), encode(iv), encode(wrapped))
            .joinToString(SEPARATOR)
    }

    /** PBKDF2-HMAC-SHA256, single 32-byte block — a 256-bit KEK for AES key wrapping. */
    private fun deriveKek(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKey {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return SecretKeySpec(result, "AES")
    }

    // Hex rather than android.util.Base64 so the envelope round-trip is pure JVM and
    // unit-testable off-device (matching VaultCrypto / PinHasher).
    private fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun decode(value: String): ByteArray {
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(value[i * 2], 16)
            val lo = Character.digit(value[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val SALT_BYTES = 16
        const val ITERATIONS = 120_000
        const val VERSION = 1
        const val SEPARATOR = ":"

        const val HEX = "0123456789abcdef"
    }
}
