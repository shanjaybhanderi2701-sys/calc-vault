package com.appblish.calculatorvault.vault.crypto

import android.content.Context
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What happened when Settings → PIN Recovery asked to re-wrap a recovery slot (Wrap B or C). */
enum class RecoveryUpdateOutcome {
    /** The slot now wraps the same DEK under the new secret — done. */
    UPDATED,

    /**
     * No unlocked **real**-vault session (missing passphrase, or the caller is inside a decoy
     * space). Recovery management is a real-vault-only, post-unlock operation, so the change
     * is refused rather than derived from the wrong namespace.
     */
    NO_SESSION,

    /** `.CalcVault/` is unreachable (All Files Access not granted). Nothing changed. */
    STORAGE_UNAVAILABLE,

    /** No key file exists yet (nothing ever hidden, so no recovery to manage). Nothing changed. */
    NO_KEY_FILE,

    /** The session PIN did not unwrap the DEK, or the write failed. Old slot left intact. */
    FAILED,
}

/**
 * Seam between Settings → PIN Recovery (regenerate recovery code / change security question)
 * and the on-disk key envelope ([VaultKeyFile]). It re-wraps a single recovery slot — Wrap C
 * (recovery code) or Wrap B (security answer) — over the **same** immutable DEK via the W1
 * envelope's `replaceRecoveryCodeWrap` / `replaceSecurityAnswerWrap`. No blob is ever
 * re-encrypted, and the PIN wrap and the other recovery wrap are untouched, so all existing
 * unlock paths keep working.
 *
 * Modeled as a `fun`-free interface (two methods) so the Settings ViewModel is unit-testable
 * off-device against a fake, mirroring [VaultReKeyer] for the change-PIN path.
 */
interface RecoveryReWrapper {
    /** Regenerate Wrap C: re-wrap the DEK under [newCode] (the old recovery code stops working). */
    suspend fun replaceRecoveryCode(newCode: String): RecoveryUpdateOutcome

    /** Change Wrap B: re-wrap the DEK under [newAnswer] (the old security answer stops working). */
    suspend fun replaceSecurityAnswer(newAnswer: String): RecoveryUpdateOutcome
}

/**
 * Device implementation over the **real** vault's `.vaultkey`. The PIN comes from the live
 * [VaultSession] (the user is already unlocked in Settings); recovery is real-vault-only, so a
 * decoy namespace resolves to [RecoveryUpdateOutcome.NO_SESSION]. Each mutation unwraps the DEK
 * with the session PIN (Wrap A) and re-wraps only the requested slot.
 */
class VaultKeyFileRecoveryReWrapper(
    context: Context,
) : RecoveryReWrapper {
    private val appContext = context.applicationContext

    override suspend fun replaceRecoveryCode(newCode: String): RecoveryUpdateOutcome =
        mutate { keyFile, dek -> keyFile.replaceRecoveryCodeWrap(dek, newCode) }

    override suspend fun replaceSecurityAnswer(newAnswer: String): RecoveryUpdateOutcome =
        mutate { keyFile, dek -> keyFile.replaceSecurityAnswerWrap(dek, newAnswer) }

    private suspend fun mutate(block: (VaultKeyFile, javax.crypto.SecretKey) -> Unit): RecoveryUpdateOutcome =
        withContext(Dispatchers.IO) {
            val pin = VaultSession.passphrase
            if (pin == null || VaultSession.namespace.isNotEmpty()) return@withContext RecoveryUpdateOutcome.NO_SESSION
            if (!StoragePermissions.hasAllFilesAccess(appContext)) {
                return@withContext RecoveryUpdateOutcome.STORAGE_UNAVAILABLE
            }
            val keyFile = VaultKeyFile(VaultStorage.keyFile(appContext, namespace = ""))
            if (!keyFile.exists()) return@withContext RecoveryUpdateOutcome.NO_KEY_FILE
            try {
                val dek = keyFile.unlock(pin)
                block(keyFile, dek)
                RecoveryUpdateOutcome.UPDATED
            } catch (e: Exception) {
                android.util.Log.w("RecoveryReWrapper", "re-wrap failed: ${e.javaClass.simpleName}")
                RecoveryUpdateOutcome.FAILED
            }
        }
}
