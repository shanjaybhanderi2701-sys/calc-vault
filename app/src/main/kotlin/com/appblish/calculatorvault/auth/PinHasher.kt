package com.appblish.calculatorvault.auth

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Salted, iterated hashing for the vault's numeric secrets — the real PIN, every decoy
 * PIN, and the security-answer used for recovery. Nothing is ever stored in cleartext:
 * [hash] returns an opaque `iterations:salt:digest` token and [verify] compares in
 * constant time.
 *
 * The KDF is PBKDF2 built directly on `Hmac-SHA256`, implemented here rather than via
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")` because that algorithm is only guaranteed
 * from API 26, and the app ships `minSdk 24`. `Mac("HmacSHA256")` is available on every
 * supported level, keeps this class dependency-free and pure-JVM, and — crucially — lets
 * the whole credential layer be unit-tested off-device.
 *
 * A 4-digit PIN has only 10 000 possibilities, so iteration count is not a defence
 * against an attacker who can already read app-private storage; the real protection is
 * the hardware-keystore-backed [EncryptedCredentialStore] the tokens live in. The salt +
 * iterations exist so tokens are not trivially rainbow-tabled and two identical PINs do
 * not produce identical stored values.
 */
object PinHasher {
    private const val ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SEPARATOR = ':'

    private val random = SecureRandom()

    /** Hash [secret] with a fresh random salt, returning a self-describing token. */
    fun hash(secret: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = pbkdf2(secret, salt, ITERATIONS)
        return listOf(ITERATIONS.toString(), toHex(salt), toHex(digest)).joinToString(SEPARATOR.toString())
    }

    /** Constant-time check of [secret] against a [token] previously produced by [hash]. */
    fun verify(
        secret: String,
        token: String,
    ): Boolean {
        val parts = token.split(SEPARATOR)
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = fromHex(parts[1]) ?: return false
        val expected = fromHex(parts[2]) ?: return false
        val actual = pbkdf2(secret, salt, iterations)
        return constantTimeEquals(expected, actual)
    }

    /**
     * Single-block PBKDF2 (RFC 8018) over Hmac-SHA256. The derived key is exactly one
     * HMAC block (32 bytes), which is plenty for a comparison token, so no block
     * concatenation is needed.
     */
    private fun pbkdf2(
        secret: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        // U1 = PRF(secret, salt || INT_32_BE(1))
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return result
    }

    private fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun fromHex(value: String): ByteArray? {
        if (value.length % 2 != 0) return null
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(value[i * 2])
            val lo = hexDigit(value[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

    private const val HEX = "0123456789abcdef"
}
