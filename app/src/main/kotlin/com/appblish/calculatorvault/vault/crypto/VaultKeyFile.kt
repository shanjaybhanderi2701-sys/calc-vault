package com.appblish.calculatorvault.vault.crypto

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The vault's **recoverable** data key, persisted in the hidden public `.CalcVault/` folder so
 * it survives app uninstall alongside the blobs.
 *
 * ## Envelope encryption (PIN Recovery spec §1 — the crypto foundation)
 *
 * There is exactly **one immutable random master DEK** (256-bit AES, [VaultCrypto.newKey]) that
 * encrypts every blob and the index. That DEK never changes for the life of the vault. It is
 * **wrapped independently by up to three secrets**, each into its own slot with its own PBKDF2
 * salt and GCM IV:
 *
 *  - **Wrap A ← PIN** — always present; created at first setup.
 *  - **Wrap B ← security answer** (normalized, [RecoverySecrets.normalizeAnswer]) — added when
 *    the user configures PIN recovery.
 *  - **Wrap C ← recovery code** (normalized, [RecoverySecrets.normalizeRecoveryCode]) — likewise.
 *
 * **Any one** of the three secrets unwraps the **same** DEK, so any single path recovers the
 * whole vault. No secret is ever stored, transmitted, or logged — only the salt/IV/ciphertext
 * of each wrap touches disk, and a wrong secret fails the GCM tag ([WrongPassphraseException]).
 * There is deliberately **no master/backdoor slot**: lose all three secrets and the data is
 * unrecoverable by design (spec §1.5).
 *
 * **Recovery is unwrap + re-wrap, never bulk re-encrypt.** Setting up recovery, changing the
 * PIN, resetting the PIN via a recovery path, or regenerating a secret all operate purely on
 * the small wrap slots — the DEK and therefore every encrypted blob are never touched. See
 * [setUpRecovery], [rewrap], [replacePinWrap], [replaceSecurityAnswerWrap],
 * [replaceRecoveryCodeWrap].
 *
 * ## File format
 *
 * v2 (written by this class) is line-oriented ASCII:
 * ```
 * CVKEY2
 * A:<iterations>:<saltHex>:<ivHex>:<wrappedHex>
 * B:<iterations>:<saltHex>:<ivHex>:<wrappedHex>   (present once recovery is set up)
 * C:<iterations>:<saltHex>:<ivHex>:<wrappedHex>   (present once recovery is set up)
 * ```
 * v1 (the pre-recovery single-PIN-wrap format, `<version>:<iterations>:<salt>:<iv>:<wrapped>`
 * on one line) is still **read** — an existing shipped vault opens unchanged and is silently
 * upgraded to v2 the next time a slot is written (PIN change / recovery setup).
 *
 * PBKDF2 is implemented on `Mac("HmacSHA256")` (as in `auth.PinHasher`) rather than
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")`, which is only guaranteed from API 26 while the
 * app ships `minSdk 24`. Pure JVM → the whole envelope round-trip is unit-testable off-device.
 */
class VaultKeyFile(
    private val file: File,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Thrown by an unlock when the supplied secret does not decrypt its wrap slot. */
    class WrongPassphraseException : GeneralSecurityException("Wrong vault passphrase")

    /** Thrown when the requested wrap slot is not present (e.g. recovery not set up). */
    class NoSuchWrapException(
        kind: WrapKind
    ) : GeneralSecurityException("No $kind wrap in vault key file")

    /** The three independent unlock methods, each mapped to one wrap slot. */
    enum class WrapKind(
        val id: String
    ) {
        /** Wrap A — derived from the PIN. Always present. */
        PIN("A"),

        /** Wrap B — derived from the normalized security answer. */
        SECURITY_ANSWER("B"),

        /** Wrap C — derived from the normalized recovery code. */
        RECOVERY_CODE("C"),
    }

    /** True if a wrapped key already exists (vault was set up, possibly before a reinstall). */
    fun exists(): Boolean = file.exists()

    /** True once BOTH recovery wraps (security answer + recovery code) are configured. */
    fun isRecoveryConfigured(): Boolean {
        if (!file.exists()) return false
        val slots = readSlots()
        return slots.containsKey(WrapKind.SECURITY_ANSWER) && slots.containsKey(WrapKind.RECOVERY_CODE)
    }

    /**
     * Unwrap the DEK with the PIN, or create+persist a fresh DEK on first setup.
     * Idempotent for a given PIN: the same PIN always yields the same DEK.
     */
    fun unlockOrCreate(passphrase: String): SecretKey = if (file.exists()) unlock(passphrase) else create(passphrase)

    /** Unwrap the DEK via Wrap A (the PIN). Throws [WrongPassphraseException] on a bad PIN. */
    fun unlock(passphrase: String): SecretKey = unwrap(WrapKind.PIN, passphrase)

    /** Unwrap the DEK via Wrap B (the security answer). The answer is normalized internally. */
    fun unlockWithAnswer(securityAnswer: String): SecretKey =
        unwrap(WrapKind.SECURITY_ANSWER, RecoverySecrets.normalizeAnswer(securityAnswer))

    /** Unwrap the DEK via Wrap C (the recovery code). The code is normalized internally. */
    fun unlockWithRecoveryCode(recoveryCode: String): SecretKey =
        unwrap(WrapKind.RECOVERY_CODE, RecoverySecrets.normalizeRecoveryCode(recoveryCode))

    /**
     * Configure PIN recovery: unwrap the DEK with the current [pin], then add Wrap B (from the
     * [securityAnswer]) and Wrap C (from the [recoveryCode]) for the **same** DEK, and persist
     * atomically. Pure unwrap + wrap — the DEK and every blob are untouched. Overwrites any
     * existing B/C slots (idempotent re-setup). Throws [WrongPassphraseException] if [pin] does
     * not unwrap the vault.
     */
    fun setUpRecovery(
        pin: String,
        securityAnswer: String,
        recoveryCode: String,
    ) {
        check(file.exists()) { "No vault key file to add recovery wraps to" }
        val dek = unlock(pin)
        val slots = readSlots().toMutableMap()
        slots[WrapKind.SECURITY_ANSWER] =
            wrap(dek, WrapKind.SECURITY_ANSWER, RecoverySecrets.normalizeAnswer(securityAnswer))
        slots[WrapKind.RECOVERY_CODE] =
            wrap(dek, WrapKind.RECOVERY_CODE, RecoverySecrets.normalizeRecoveryCode(recoveryCode))
        writeSlots(slots)
    }

    /**
     * Envelope re-key for a PIN change (APP-245): unwrap the DEK with [oldPassphrase], re-wrap
     * it into Wrap A under [newPassphrase] with a fresh salt + IV, and atomically replace the
     * key file. Any existing recovery wraps (B/C) are **preserved** — they wrap the same DEK, so
     * they keep working after the PIN change. The DEK never changes; every blob and the encrypted
     * index stay readable. Afterwards the old PIN fails the GCM tag like any wrong passphrase.
     *
     * Throws [WrongPassphraseException] — file untouched — when [oldPassphrase] does not unwrap
     * the stored key, and [IllegalStateException] when there is no key file.
     */
    fun rewrap(
        oldPassphrase: String,
        newPassphrase: String,
    ) {
        check(file.exists()) { "No vault key file to re-wrap" }
        val dek = unlock(oldPassphrase)
        replacePinWrap(dek, newPassphrase)
    }

    /**
     * Re-create Wrap A under [newPin] for a DEK already recovered via **any** unlock method
     * (the recovery flow, spec §5.2: unlock via B or C, then set a new PIN). Preserves B/C.
     * The caller must pass a DEK obtained from this same key file; this method does not verify
     * provenance (that is the unlock's job).
     */
    fun replacePinWrap(
        dek: SecretKey,
        newPin: String,
    ) {
        val slots = readSlots().toMutableMap()
        slots[WrapKind.PIN] = wrap(dek, WrapKind.PIN, newPin)
        writeSlots(slots)
    }

    /** Replace Wrap B under a new security answer, for a known-good [dek] (Settings, §6b). */
    fun replaceSecurityAnswerWrap(
        dek: SecretKey,
        newAnswer: String,
    ) {
        val slots = readSlots().toMutableMap()
        slots[WrapKind.SECURITY_ANSWER] =
            wrap(dek, WrapKind.SECURITY_ANSWER, RecoverySecrets.normalizeAnswer(newAnswer))
        writeSlots(slots)
    }

    /** Replace Wrap C under a regenerated recovery code, for a known-good [dek] (Settings, §6b). */
    fun replaceRecoveryCodeWrap(
        dek: SecretKey,
        newCode: String,
    ) {
        val slots = readSlots().toMutableMap()
        slots[WrapKind.RECOVERY_CODE] =
            wrap(dek, WrapKind.RECOVERY_CODE, RecoverySecrets.normalizeRecoveryCode(newCode))
        writeSlots(slots)
    }

    // --- internals -----------------------------------------------------------------------

    /** Generate a fresh DEK, wrap it under Wrap A (PIN), and persist as a new v2 file. */
    private fun create(pin: String): SecretKey {
        val dek = VaultCrypto.newKey()
        file.parentFile?.mkdirs()
        writeSlots(mapOf(WrapKind.PIN to wrap(dek, WrapKind.PIN, pin)))
        return dek
    }

    /** Unwrap [kind]'s slot with the (already-normalized) [secret]; map failures to typed exns. */
    private fun unwrap(
        kind: WrapKind,
        secret: String,
    ): SecretKey {
        val slot = readSlots()[kind] ?: throw NoSuchWrapException(kind)
        // A secret that normalizes to nothing (e.g. an all-punctuation code) can never be the
        // one that wrapped the DEK — treat it as a wrong secret, not an "empty key" crash.
        if (secret.isEmpty()) throw WrongPassphraseException()
        val kek = deriveKek(secret, slot.salt, slot.iterations)
        val raw =
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, slot.iv))
                cipher.doFinal(slot.wrapped)
            } catch (e: GeneralSecurityException) {
                throw WrongPassphraseException()
            }
        return SecretKeySpec(raw, "AES")
    }

    /** Wrap [dek] under a fresh-salt KEK derived from the (already-normalized) [secret]. */
    private fun wrap(
        dek: SecretKey,
        kind: WrapKind,
        secret: String,
    ): WrapSlot {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val kek = deriveKek(secret, salt, ITERATIONS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
        return WrapSlot(kind, ITERATIONS, salt, iv, cipher.doFinal(dek.encoded))
    }

    /** Parse the key file into wrap slots, reading either the v2 or the legacy v1 format. */
    private fun readSlots(): Map<WrapKind, WrapSlot> {
        val text = file.readText().trim()
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "Empty vault key file" }
        return if (lines[0] == MAGIC_V2) {
            lines.drop(1).map(::parseSlot).associateBy { it.kind }
        } else {
            // Legacy v1: one line `version:iterations:salt:iv:wrapped`, PIN wrap only.
            val parts = text.split(SEPARATOR)
            require(parts.size == 5) { "Malformed vault key file" }
            mapOf(
                WrapKind.PIN to
                    WrapSlot(WrapKind.PIN, parts[1].toInt(), decode(parts[2]), decode(parts[3]), decode(parts[4]))
            )
        }
    }

    private fun parseSlot(line: String): WrapSlot {
        val parts = line.split(SEPARATOR)
        require(parts.size == 5) { "Malformed wrap slot" }
        val kind = WrapKind.entries.firstOrNull { it.id == parts[0] } ?: error("Unknown wrap slot '${parts[0]}'")
        return WrapSlot(kind, parts[1].toInt(), decode(parts[2]), decode(parts[3]), decode(parts[4]))
    }

    /**
     * Serialize [slots] to the v2 format and replace the file **atomically** (temp write +
     * rename), so a crash mid-write leaves the previous envelope byte-for-byte intact and can
     * never strand the vault. Wrap A must always be present.
     */
    private fun writeSlots(slots: Map<WrapKind, WrapSlot>) {
        check(slots.containsKey(WrapKind.PIN)) { "Vault key file must always carry the PIN wrap" }
        file.parentFile?.mkdirs()
        val body =
            buildString {
                append(MAGIC_V2)
                // Stable slot order (A, B, C) — deterministic output, no dependence on map order.
                for (kind in WrapKind.entries) {
                    val slot = slots[kind] ?: continue
                    append('\n').append(serializeSlot(slot))
                }
            }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not atomically replace the vault key file")
        }
    }

    private fun serializeSlot(slot: WrapSlot): String =
        listOf(slot.kind.id, slot.iterations.toString(), encode(slot.salt), encode(slot.iv), encode(slot.wrapped))
            .joinToString(SEPARATOR)

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

    /** One independent wrap of the DEK: which secret, PBKDF2 iterations, salt, GCM IV, ciphertext. */
    private class WrapSlot(
        val kind: WrapKind,
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val wrapped: ByteArray,
    )

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val SALT_BYTES = 16
        const val ITERATIONS = 120_000
        const val SEPARATOR = ":"
        const val MAGIC_V2 = "CVKEY2"

        const val HEX = "0123456789abcdef"
    }
}
