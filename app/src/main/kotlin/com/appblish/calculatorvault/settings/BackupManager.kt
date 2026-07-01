package com.appblish.calculatorvault.settings

import com.appblish.calculatorvault.auth.CredentialStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected, restorable backup of the whole vault configuration — the credential
 * tokens (hashed PINs, decoy list, recovery material) and the [VaultSettings]. The backup
 * is a single self-describing blob the owner can move off-device (email, cloud, another
 * phone) and restore later; nothing inside it is readable without the backup password.
 *
 * Crypto: the password is stretched with PBKDF2-HMAC-SHA1 (universally available from
 * minSdk 24, unlike SHA-256 which needs API 26) into a 256-bit key, and the payload is
 * sealed with AES-256-GCM. A fresh random salt + IV are generated per backup, so the same
 * password never produces the same ciphertext and the tag authenticates the whole payload
 * (a tampered or truncated backup fails to restore rather than restoring garbage).
 *
 * The blob is `MAGIC.version.saltHex.ivHex.cipherHex` — all-hex fields avoid any Base64
 * dependency (java.util.Base64 is API 26+) and keep the format grep-inspectable.
 */
class BackupManager(
    private val credentialStore: CredentialStore,
    private val settingsStore: SettingsStore,
) {
    /** Build an encrypted backup blob sealed under [password]. */
    suspend fun createBackup(password: String): String {
        require(password.length >= MIN_PASSWORD) { "Backup password must be at least $MIN_PASSWORD characters." }
        val namespaced =
            buildMap {
                credentialStore.exportRaw().forEach { (k, v) -> put("$CRED_PREFIX$k", v) }
                settingsStore.exportRaw().forEach { (k, v) -> put("$SET_PREFIX$k", v) }
            }
        return seal(serialize(namespaced), password)
    }

    /**
     * Restore a backup produced by [createBackup]. Overwrites all current credential and
     * settings state. Throws [BackupException] if the blob is malformed or the password is
     * wrong (GCM tag mismatch) — nothing is written in that case.
     */
    suspend fun restoreBackup(
        blob: String,
        password: String,
    ) {
        val plaintext = open(blob, password)
        val namespaced = deserialize(plaintext)
        val cred = mutableMapOf<String, String>()
        val settings = mutableMapOf<String, String>()
        namespaced.forEach { (k, v) ->
            when {
                k.startsWith(CRED_PREFIX) -> cred[k.removePrefix(CRED_PREFIX)] = v
                k.startsWith(SET_PREFIX) -> settings[k.removePrefix(SET_PREFIX)] = v
            }
        }
        credentialStore.importRaw(cred)
        settingsStore.importRaw(settings)
    }

    // --- serialization: hex(key):hex(value) per line, fully reversible ---

    private fun serialize(values: Map<String, String>): ByteArray =
        values.entries
            .joinToString("\n") { (k, v) ->
                "${toHex(k.toByteArray(Charsets.UTF_8))}:${toHex(v.toByteArray(Charsets.UTF_8))}"
            }.toByteArray(Charsets.UTF_8)

    private fun deserialize(bytes: ByteArray): Map<String, String> {
        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty()) return emptyMap()
        return text
            .split("\n")
            .filter { it.isNotEmpty() }
            .associate { line ->
                val parts = line.split(":")
                if (parts.size != 2) throw BackupException("Corrupt backup record.")
                String(fromHex(parts[0]), Charsets.UTF_8) to String(fromHex(parts[1]), Charsets.UTF_8)
            }
    }

    // --- crypto ---

    private fun seal(
        plaintext: ByteArray,
        password: String,
    ): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return listOf(MAGIC, VERSION.toString(), toHex(salt), toHex(iv), toHex(ciphertext)).joinToString(FIELD_SEP)
    }

    private fun open(
        blob: String,
        password: String,
    ): ByteArray {
        val fields = blob.trim().split(FIELD_SEP)
        if (fields.size != 5 || fields[0] != MAGIC) throw BackupException("Not a CalcVault backup.")
        if (fields[1].toIntOrNull() != VERSION) throw BackupException("Unsupported backup version.")
        val salt = fromHex(fields[2])
        val iv = fromHex(fields[3])
        val ciphertext = fromHex(fields[4])
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // GCM tag failure = wrong password or tampered payload. Never leak partial data.
            throw BackupException("Wrong password or corrupt backup.", e)
        }
    }

    private fun deriveKey(
        password: String,
        salt: ByteArray,
    ): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun fromHex(hex: String): ByteArray {
        if (hex.length % 2 != 0) throw BackupException("Corrupt backup encoding.")
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            if (hi < 0 || lo < 0) throw BackupException("Corrupt backup encoding.")
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private companion object {
        const val MAGIC = "CVBACKUP"
        const val VERSION = 1
        const val FIELD_SEP = "."
        const val CRED_PREFIX = "cred:"
        const val SET_PREFIX = "set:"
        const val MIN_PASSWORD = 6
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_BITS = 256
        const val PBKDF2_ITERATIONS = 210_000
        const val PBKDF2_ALGO = "PBKDF2WithHmacSHA1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val HEX = "0123456789abcdef".toCharArray()
    }
}

/** Thrown when a backup blob cannot be parsed, decrypted, or authenticated. */
class BackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
