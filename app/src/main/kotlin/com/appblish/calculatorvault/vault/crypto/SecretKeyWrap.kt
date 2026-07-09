package com.appblish.calculatorvault.vault.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The vault's single **key-wrapping primitive** (APP-322): wrap one immutable data key
 * (DEK) under a key-encryption key (KEK) derived from a user secret with PBKDF2-HMAC-SHA256,
 * sealed with AES-256-GCM.
 *
 * This is the one crypto boundary the Security Engineer signs off (spec §1). Both the
 * PIN wrap on disk ([VaultKeyFile], "Wrap A") and the two recovery wraps ([RecoveryEnvelope],
 * "Wrap B ← security answer", "Wrap C ← recovery code") are the *same* construction over the
 * *same* DEK — they only differ in which secret derives the KEK. Extracting it here means:
 *
 * - Any one secret unwraps the **same** DEK, because each wrap seals the identical DEK bytes.
 * - There is exactly one place to review the KDF parameters, the AEAD mode, and the
 *   "never persist the raw secret or the raw DEK" guarantee — not three copies to keep in sync.
 *
 * **Serialized form (single ASCII line, hex fields):** `iterations:saltHex:ivHex:wrappedHex`.
 * The salt and IV are random per wrap, so two wraps of the same DEK under the same secret
 * still produce different bytes on disk. `wrapped` is `GCM(KEK, iv, DEK.encoded)` — the raw
 * DEK never appears, and neither does the secret (only its PBKDF2 output, which is one-way).
 *
 * PBKDF2 runs on `Mac("HmacSHA256")` (as in `auth.PinHasher` and the original [VaultKeyFile])
 * rather than `SecretKeyFactory("PBKDF2WithHmacSHA256")`, which is only guaranteed from API 26
 * while the app ships `minSdk 24`. Pure JVM → every wrap/unwrap round-trip is unit-testable
 * off-device.
 */
object SecretKeyWrap {
    /** Thrown by [unwrap] when the supplied secret does not decrypt the wrapped DEK. */
    class WrongSecretException : GeneralSecurityException("Secret does not unwrap the key")

    /**
     * Wrap [dek] under a KEK derived from [secret] and return the serialized line. A fresh
     * random salt and IV are drawn from [random] on every call, so callers that re-wrap the
     * same DEK never emit identical bytes.
     */
    fun wrap(
        dek: SecretKey,
        secret: String,
        random: SecureRandom = SecureRandom(),
    ): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val kek = deriveKek(secret, salt, ITERATIONS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
        val wrapped = cipher.doFinal(dek.encoded)
        return listOf(ITERATIONS.toString(), encode(salt), encode(iv), encode(wrapped)).joinToString(SEPARATOR)
    }

    /**
     * Unwrap the DEK from a [serialized] `iterations:salt:iv:wrapped` line using [secret].
     * Throws [WrongSecretException] when the secret is wrong (the GCM tag fails) and
     * [IllegalArgumentException] when the line is malformed.
     */
    fun unwrap(
        serialized: String,
        secret: String,
    ): SecretKey {
        val parts = serialized.trim().split(SEPARATOR)
        require(parts.size == FIELD_COUNT) { "Malformed wrap (expected $FIELD_COUNT fields)" }
        val iterations = parts[0].toInt()
        val salt = decode(parts[1])
        val iv = decode(parts[2])
        val wrapped = decode(parts[3])
        val kek = deriveKek(secret, salt, iterations)
        val raw =
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
                cipher.doFinal(wrapped)
            } catch (e: GeneralSecurityException) {
                throw WrongSecretException()
            }
        return SecretKeySpec(raw, "AES")
    }

    /** PBKDF2-HMAC-SHA256, single 32-byte block — a 256-bit KEK for AES key wrapping. */
    private fun deriveKek(
        secret: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKey {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
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

    // Hex rather than android.util.Base64 so the round-trip is pure JVM and unit-testable
    // off-device (matching VaultCrypto / PinHasher / VaultKeyFile).
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

    // Parameters are intentionally identical to the original VaultKeyFile "Wrap A" envelope
    // so the extraction is behaviour-preserving: AES-256-GCM, 12-byte IV, 128-bit tag,
    // 16-byte salt, PBKDF2-HMAC-SHA256 at 120k iterations.
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val SALT_BYTES = 16
    private const val ITERATIONS = 120_000
    private const val SEPARATOR = ":"
    private const val FIELD_COUNT = 4
    private const val HEX = "0123456789abcdef"
}
