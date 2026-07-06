package com.appblish.calculatorvault.vault.crypto

import android.content.Context
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What happened when the change-PIN flow asked for the vault key envelope to be re-keyed. */
enum class ReKeyOutcome {
    /** `.vaultkey` now wraps the DEK under the new PIN — safe to commit the credential. */
    REWRAPPED,

    /** No key file exists yet (nothing ever hidden) — nothing to re-key, safe to commit. */
    NO_KEY_FILE,

    /**
     * `.CalcVault/` is unreachable because All Files Access is not currently granted. The
     * PIN change MUST be blocked: committing the new auth token while the key file still
     * wraps under the old PIN is exactly the stranded-vault bug this seam exists to prevent.
     */
    STORAGE_UNAVAILABLE,

    /**
     * The key file exists but could not be re-wrapped — the entered current PIN does not
     * unwrap it (a pre-fix PIN change already diverged token from envelope) or the write
     * failed. The old envelope is left intact and the PIN change must not proceed.
     */
    FAILED,
}

/**
 * Seam between Settings → Change PIN and the on-disk key envelope ([VaultKeyFile]), so the
 * commit-ordering invariant — `CredentialStore.setRealPin` runs **only after** the envelope
 * follows the new PIN — is unit-testable without a device (APP-245).
 */
fun interface VaultReKeyer {
    suspend fun rewrap(
        oldPassphrase: String,
        newPassphrase: String,
    ): ReKeyOutcome
}

/**
 * Device implementation over the real vault's `.vaultkey`. [namespace] defaults to the root
 * (real) vault — the change-PIN flow only ever rotates the *real* credential, and each decoy
 * space wraps its own key under its own passphrase in its own sub-directory. Tests pass a
 * scratch namespace so they never touch a real vault's root data.
 */
class VaultKeyFileReKeyer(
    context: Context,
    private val namespace: String = "",
) : VaultReKeyer {
    private val appContext = context.applicationContext

    override suspend fun rewrap(
        oldPassphrase: String,
        newPassphrase: String,
    ): ReKeyOutcome =
        withContext(Dispatchers.IO) {
            if (!StoragePermissions.hasAllFilesAccess(appContext)) return@withContext ReKeyOutcome.STORAGE_UNAVAILABLE
            val keyFile = VaultKeyFile(VaultStorage.keyFile(appContext, namespace))
            if (!keyFile.exists()) return@withContext ReKeyOutcome.NO_KEY_FILE
            try {
                keyFile.rewrap(oldPassphrase, newPassphrase)
                ReKeyOutcome.REWRAPPED
            } catch (e: Exception) {
                android.util.Log.w("VaultReKeyer", "re-wrap failed: ${e.javaClass.simpleName}")
                ReKeyOutcome.FAILED
            }
        }
}
