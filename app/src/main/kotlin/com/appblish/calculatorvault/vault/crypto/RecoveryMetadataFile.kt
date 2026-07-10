package com.appblish.calculatorvault.vault.crypto

import java.io.File
import java.io.IOException

/**
 * Survive-uninstall, **non-secret** display metadata for PIN recovery — currently just the
 * security-question prompt the user chose. It lives beside the [VaultKeyFile] in the hidden
 * public `.CalcVault/<namespace>/` folder so it outlives an uninstall exactly like the wrap
 * slots and the DEK do (PIN Recovery spec §1, APP-338).
 *
 * ## Why here and not app-private `EncryptedSharedPreferences`
 *
 * The chosen prompt (e.g. *"What was your first pet's name?"*) is **display metadata, not a
 * secret**: the *answer* is the secret and never leaves its PBKDF2-wrapped Wrap B in the key
 * file. Storing the prompt in app-private `EncryptedSharedPreferences` (its `MasterKey` in the
 * Android Keystore) meant it was wiped on uninstall while the wraps survived, so after a
 * reinstall the security-question recovery path had a working answer-wrap but **no prompt to
 * show** ([configuredQuestion] returned `null`). Co-locating the prompt with the wraps it
 * labels fixes that without weakening §1: **no secret, hash, salt, IV, or key material is ever
 * written here** — only the plaintext prompt, which is public by nature (it is shown to anyone
 * who reaches the recovery screen).
 *
 * ## Format (v1)
 *
 * Line-oriented ASCII, mirroring [VaultKeyFile]'s pure-JVM, off-device-testable style:
 * ```
 * CVRMETA1
 * <question prompt — the entire remainder of the file, UTF-8, may contain any character>
 * ```
 * The prompt is the raw remainder after the first newline, so colons / spaces / newlines in a
 * custom question round-trip untouched (no escaping needed). Writes are **atomic** (temp +
 * rename) so a crash mid-write can never leave a torn prompt, and an unreadable / malformed /
 * absent file simply reads back as "no prompt" ([readQuestion] returns `null`).
 */
class RecoveryMetadataFile(
    private val file: File,
) {
    /** True if a metadata file exists (recovery prompt may have been recorded here). */
    fun exists(): Boolean = file.exists()

    /**
     * The stored question prompt, or `null` if the file is absent, unreadable, malformed, or
     * carries a blank prompt. Never throws — a broken metadata file must degrade to "unknown
     * prompt", never crash a recovery screen.
     */
    fun readQuestion(): String? {
        if (!file.exists()) return null
        val text =
            try {
                file.readText()
            } catch (e: IOException) {
                return null
            }
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        if (text.substring(0, newline).trim() != MAGIC) return null
        return text.substring(newline + 1).ifBlank { null }
    }

    /**
     * Persist [question] as the survive-uninstall prompt, replacing the file **atomically**
     * (temp write + rename). Throws [IOException] if the rename fails, so the caller can react
     * rather than silently believe the prompt was recorded.
     */
    fun writeQuestion(question: String) {
        file.parentFile?.mkdirs()
        val body = "$MAGIC\n$question"
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not atomically replace the recovery metadata file")
        }
    }

    private companion object {
        const val MAGIC = "CVRMETA1"
    }
}
