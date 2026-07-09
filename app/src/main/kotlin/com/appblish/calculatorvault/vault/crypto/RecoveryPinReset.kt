package com.appblish.calculatorvault.vault.crypto

import android.content.Context
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

/** Which recovery secret the forgot-PIN flow is verifying against ([VaultKeyFile] Wrap B / Wrap C). */
enum class RecoveryMethod(
    /** Persisted id for the backoff counter file — stable across enum re-ordering. */
    val id: String,
    /** The route argument W2 hands in (`answer` / `code`). */
    val arg: String,
) {
    SECURITY_ANSWER("B", "answer"),
    RECOVERY_CODE("C", "code"),
    ;

    companion object {
        /** Map a route arg back to a method; anything but `code` is the security-answer path. */
        fun fromArg(arg: String?): RecoveryMethod = if (arg == RECOVERY_CODE.arg) RECOVERY_CODE else SECURITY_ANSWER
    }
}

/** Result of verifying a recovery secret (unwrapping the DEK via Wrap B/C). */
sealed interface RecoveryVerifyOutcome {
    /** Secret correct — the DEK is now held for the pending set-new-PIN step. */
    data object Verified : RecoveryVerifyOutcome

    /**
     * Wrong secret. [remainingLockoutMillis] is the lockout this failure just imposed (0 while
     * still inside the fat-finger grace) so the UI can show the escalating wait.
     */
    data class WrongSecret(
        val remainingLockoutMillis: Long,
    ) : RecoveryVerifyOutcome

    /** Entry is still locked out from earlier failures; no unwrap was attempted this time. */
    data class LockedOut(
        val remainingLockoutMillis: Long,
    ) : RecoveryVerifyOutcome

    /**
     * Recovery can't run here: All Files Access is missing, or the vault has no key file /
     * no recovery wraps. The honest "nothing to reset" surface (spec §1.5) — never a false
     * "contact support".
     */
    data object Unavailable : RecoveryVerifyOutcome
}

/** Result of committing a new PIN after a successful verify (re-wrap Wrap A only). */
enum class RecoveryResetOutcome {
    /** `.vaultkey` now wraps the DEK under the new PIN (Wrap A rotated; B/C untouched). */
    RESET,

    /** No secret was verified first — the caller must not reach here; nothing changed. */
    NOT_VERIFIED,

    /** `.CalcVault/` became unreachable (All Files Access revoked mid-flow). Nothing changed. */
    STORAGE_UNAVAILABLE,

    /** The re-wrap write failed. The old envelope is left intact; the PIN was not reset. */
    FAILED,
}

/**
 * The W3 recovery-unlock seam (PIN Recovery spec §5.2, W0 screens 09/10 → 11). One instance
 * drives a single forgot-PIN attempt:
 *
 *  1. [verify] unwraps the immutable DEK via **Wrap B** (security answer) or **Wrap C**
 *     (recovery code), gated by the [RecoveryBackoff] schedule, and holds the DEK.
 *  2. [resetPin] re-creates **Wrap A only** for that same DEK under the new PIN — no bulk
 *     re-encrypt, and Wrap B / Wrap C are preserved so every recovery path keeps working
 *     (spec §1.3). The caller commits the auth credential afterwards, mirroring the change-PIN
 *     ordering invariant (envelope first, then [com.appblish.calculatorvault.auth.CredentialStore.setRealPin]).
 *
 * Modeled as an interface so the recovery ViewModel is unit-testable off-device against a fake.
 */
interface RecoveryPinReset {
    /** Milliseconds still to wait before [method] may be tried again (0 = ready now). */
    suspend fun lockoutRemainingMillis(method: RecoveryMethod): Long

    /** Attempt to unwrap the DEK with [secret] via [method]; on success the DEK is held for [resetPin]. */
    suspend fun verify(
        method: RecoveryMethod,
        secret: String,
    ): RecoveryVerifyOutcome

    /** Re-wrap Wrap A under [newPin] for the DEK held by the last successful [verify]. */
    suspend fun resetPin(newPin: String): RecoveryResetOutcome
}

/**
 * Device [RecoveryPinReset] over the **real** vault's `.vaultkey` (namespace `""`). The
 * forgot-PIN flow is reached from the calculator gate with no live session, so this targets
 * the real vault explicitly rather than [com.appblish.calculatorvault.vault.VaultSession].
 * [now] is injected so the backoff is deterministic under test.
 *
 * State: the DEK from a successful [verify] is held in memory only for the life of this
 * instance (one flow), never surfaced. [RecoveryGraph.newPinReset] hands out a fresh instance
 * per flow so a stale DEK can never leak across attempts.
 */
class VaultKeyFileRecoveryPinReset(
    context: Context,
    private val namespace: String = "",
    private val now: () -> Long = { System.currentTimeMillis() },
) : RecoveryPinReset {
    private val appContext = context.applicationContext

    @Volatile
    private var verifiedDek: SecretKey? = null

    private fun keyFile(): VaultKeyFile = VaultKeyFile(VaultStorage.keyFile(appContext, namespace))

    private fun backoffStore(): RecoveryBackoffStore =
        FileRecoveryBackoffStore(VaultStorage.recoveryBackoffFile(appContext, namespace))

    override suspend fun lockoutRemainingMillis(method: RecoveryMethod): Long =
        withContext(Dispatchers.IO) {
            val attempt = backoffStore().read(method)
            RecoveryBackoff.remainingLockoutMillis(attempt.failures, attempt.lastFailureAtMillis, now())
        }

    override suspend fun verify(
        method: RecoveryMethod,
        secret: String,
    ): RecoveryVerifyOutcome =
        withContext(Dispatchers.IO) {
            if (!StoragePermissions.hasAllFilesAccess(appContext)) return@withContext RecoveryVerifyOutcome.Unavailable
            val keyFile = keyFile()
            if (!keyFile.exists() || !keyFile.isRecoveryConfigured()) {
                return@withContext RecoveryVerifyOutcome.Unavailable
            }
            val store = backoffStore()
            val before = store.read(method)
            val locked = RecoveryBackoff.remainingLockoutMillis(before.failures, before.lastFailureAtMillis, now())
            if (locked > 0L) return@withContext RecoveryVerifyOutcome.LockedOut(locked)

            try {
                verifiedDek =
                    when (method) {
                        RecoveryMethod.SECURITY_ANSWER -> keyFile.unlockWithAnswer(secret)
                        RecoveryMethod.RECOVERY_CODE -> keyFile.unlockWithRecoveryCode(secret)
                    }
                store.clear(method)
                RecoveryVerifyOutcome.Verified
            } catch (e: GeneralSecurityException) {
                // WrongPassphraseException / NoSuchWrapException — a wrong secret, count it.
                store.recordFailure(method, now())
                val after = store.read(method)
                RecoveryVerifyOutcome.WrongSecret(
                    RecoveryBackoff.remainingLockoutMillis(after.failures, after.lastFailureAtMillis, now()),
                )
            }
        }

    override suspend fun resetPin(newPin: String): RecoveryResetOutcome =
        withContext(Dispatchers.IO) {
            val dek = verifiedDek ?: return@withContext RecoveryResetOutcome.NOT_VERIFIED
            if (!StoragePermissions.hasAllFilesAccess(appContext)) {
                return@withContext RecoveryResetOutcome.STORAGE_UNAVAILABLE
            }
            try {
                keyFile().replacePinWrap(dek, newPin)
                verifiedDek = null
                RecoveryResetOutcome.RESET
            } catch (e: Exception) {
                android.util.Log.w("RecoveryPinReset", "Wrap A re-wrap failed: ${e.javaClass.simpleName}")
                RecoveryResetOutcome.FAILED
            }
        }
}
