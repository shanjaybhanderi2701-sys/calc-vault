package com.appblish.calculatorvault.vault.crypto

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * The vault's **PIN wrap** ("Wrap A") of the master data key — the DEK wrapped under a KEK
 * derived from the entry PIN — persisted in the hidden public `.CalcVault/` folder so it
 * survives app uninstall alongside the blobs.
 *
 * Phase 2's original [VaultKeyStore] kept the data key (DEK) in EncryptedSharedPreferences
 * under an AndroidKeyStore master key — which the OS **wipes on uninstall**. That made the
 * survive-uninstall directive impossible: the blobs could live in public storage, but the
 * key to decrypt them could not. This file fixes that.
 *
 * Design (envelope encryption, spec §1):
 * 1. A random 256-bit **DEK** encrypts the blobs and index (via [VaultCrypto]). It is
 *    immutable — created once and never changed.
 * 2. The DEK is wrapped (AES-256-GCM under a PBKDF2 KEK) by the PIN and written to
 *    `.vaultkey`. The wrap itself is the shared [SecretKeyWrap] primitive; this class only
 *    owns the file's version framing and the atomic on-disk lifecycle.
 *
 * On reinstall the file is still on shared storage, so re-entering the same PIN re-derives
 * the KEK, unwraps the DEK, and the vault is readable again. A wrong PIN fails the GCM tag,
 * surfaced as [WrongPassphraseException].
 *
 * **This is only Wrap A.** The two recovery wraps (security answer, recovery code) live in a
 * sibling [RecoveryEnvelope] and wrap the *same* DEK, so any one secret opens the vault and a
 * PIN change here provably cannot invalidate them — it only rewrites `.vaultkey` (APP-322 §1).
 *
 * On-disk format (single ASCII line): `version:iterations:saltHex:ivHex:wrappedHex`.
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
        require(parts.size == FIELD_COUNT) { "Malformed vault key file" }
        val payload = parts.drop(1).joinToString(SEPARATOR)
        return try {
            SecretKeyWrap.unwrap(payload, passphrase)
        } catch (e: SecretKeyWrap.WrongSecretException) {
            throw WrongPassphraseException()
        }
    }

    /**
     * Envelope re-key for a PIN change (APP-245): unwrap the DEK with [oldPassphrase],
     * re-wrap it under a KEK derived from [newPassphrase] with a **fresh salt + IV**, and
     * atomically replace the key file. The DEK itself never changes — every blob and the
     * encrypted index stay readable, and because the recovery wraps ([RecoveryEnvelope]) wrap
     * the same unchanged DEK, they keep working too. Afterwards the old PIN fails the GCM tag
     * exactly like any wrong passphrase.
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
        writePinWrap(dek, newPassphrase)
    }

    /**
     * (Re)create Wrap A around a **known** DEK under [passphrase], atomically. This is the
     * "re-wrap Wrap A only" step of recovery (spec §1.3 / §5.2, APP-322): after a recovery
     * path unwraps the DEK via the security answer or recovery code, the user sets a new PIN
     * and this rewrites `.vaultkey` to wrap that same DEK — files are never touched and the
     * recovery wraps stay valid. Unlike [create] it does not generate a new key, so it can
     * bind the recovered DEK to a fresh PIN.
     *
     * The write is atomic (temp write + rename), so a crash mid-write leaves the previous
     * envelope intact rather than stranding the vault behind a half-written key file.
     */
    fun writePinWrap(
        dek: SecretKey,
        passphrase: String,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(serializeWrapped(dek, passphrase))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not atomically replace the vault key file")
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
    ): String = "$VERSION$SEPARATOR${SecretKeyWrap.wrap(dek, passphrase, random)}"

    private companion object {
        const val VERSION = 1
        const val SEPARATOR = ":"

        /** version + the 4 fields from [SecretKeyWrap]: iterations, salt, iv, wrapped. */
        const val FIELD_COUNT = 5
    }
}
