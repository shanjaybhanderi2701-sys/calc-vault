package com.appblish.calculatorvault.vault.crypto

import android.content.Context
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which recovery secret the user is proving identity with (spec §5 — Wrap B vs Wrap C). */
enum class RecoveryMethod {
    /** The security answer — unwraps the DEK via Wrap B. */
    SECURITY_ANSWER,

    /** The saved recovery code — unwraps the DEK via Wrap C. */
    RECOVERY_CODE,
}

/** Result of verifying a recovery secret before offering the set-new-PIN step. */
enum class RecoveryVerifyOutcome {
    /** The secret unwrapped the DEK — proceed to set a new PIN. */
    CORRECT,

    /** The secret did not unwrap its slot — a wrong answer / wrong code (drives backoff). */
    WRONG_SECRET,

    /** Recovery was never configured for this vault — the honest "unrecoverable" dead-end. */
    NOT_CONFIGURED,

    /** `.CalcVault/` is unreachable (All Files Access not granted) — can't read the envelope. */
    STORAGE_UNAVAILABLE,
}

/** Result of the Wrap-A-only re-wrap that resets the PIN through a recovery secret. */
enum class RecoveryResetOutcome {
    /** `.vaultkey` now wraps the DEK under the new PIN; Wrap B/C untouched. */
    RESET,

    /** The recovery secret no longer unwraps — treat as a wrong attempt, nothing mutated. */
    WRONG_SECRET,

    /** Recovery is not configured — nothing to reset from. */
    NOT_CONFIGURED,

    /** All Files Access is not granted — the envelope must not be touched. */
    STORAGE_UNAVAILABLE,

    /** The unwrap succeeded but the re-wrap write failed — the old envelope is left intact. */
    FAILED,
}

/**
 * Pure, off-device core of the recovery reset (APP-325 W3), operating directly on a
 * [VaultKeyFile] so the whole flow is unit-testable without a device — matching the crypto
 * foundation it stands on.
 *
 * Recovery proves identity with the **security answer** (Wrap B) or the **recovery code**
 * (Wrap C), unwraps the one immutable DEK, and re-wraps **Wrap A only** under a new PIN
 * (spec §1.3 / §5.2). Wrap B and Wrap C — and every encrypted blob and the index — are never
 * touched: a PIN reset via recovery is unwrap + re-wrap of a single slot, never a bulk
 * re-encrypt, so the security-question and recovery-code paths keep working afterward.
 */
object RecoveryEnvelope {
    /**
     * Prove [secret] unwraps the DEK for [method] without mutating anything (the identity
     * check the UI runs before it asks for a new PIN). A degenerate / wrong secret fails the
     * GCM tag inside [VaultKeyFile] and surfaces as [RecoveryVerifyOutcome.WRONG_SECRET].
     */
    fun verify(
        keyFile: VaultKeyFile,
        method: RecoveryMethod,
        secret: String,
    ): RecoveryVerifyOutcome {
        if (!keyFile.exists() || !keyFile.isRecoveryConfigured()) return RecoveryVerifyOutcome.NOT_CONFIGURED
        return try {
            unlock(keyFile, method, secret)
            RecoveryVerifyOutcome.CORRECT
        } catch (e: VaultKeyFile.WrongPassphraseException) {
            RecoveryVerifyOutcome.WRONG_SECRET
        } catch (e: VaultKeyFile.NoSuchWrapException) {
            RecoveryVerifyOutcome.NOT_CONFIGURED
        }
    }

    /**
     * Reset the PIN: unwrap the DEK with the recovery [secret], then re-create Wrap A under
     * [newPin] with a fresh salt + IV, preserving Wrap B/C. The DEK never changes, so every
     * blob hidden before the reset stays readable and the other recovery paths keep working.
     * The unwrap runs first — a wrong secret returns [RecoveryResetOutcome.WRONG_SECRET] with
     * the envelope byte-for-byte intact, never a half-reset key file.
     */
    fun resetPin(
        keyFile: VaultKeyFile,
        method: RecoveryMethod,
        secret: String,
        newPin: String,
    ): RecoveryResetOutcome {
        if (!keyFile.exists() || !keyFile.isRecoveryConfigured()) return RecoveryResetOutcome.NOT_CONFIGURED
        val dek =
            try {
                unlock(keyFile, method, secret)
            } catch (e: VaultKeyFile.WrongPassphraseException) {
                return RecoveryResetOutcome.WRONG_SECRET
            } catch (e: VaultKeyFile.NoSuchWrapException) {
                return RecoveryResetOutcome.NOT_CONFIGURED
            }
        return try {
            keyFile.replacePinWrap(dek, newPin)
            RecoveryResetOutcome.RESET
        } catch (e: Exception) {
            RecoveryResetOutcome.FAILED
        }
    }

    private fun unlock(
        keyFile: VaultKeyFile,
        method: RecoveryMethod,
        secret: String,
    ) = when (method) {
        RecoveryMethod.SECURITY_ANSWER -> keyFile.unlockWithAnswer(secret)
        RecoveryMethod.RECOVERY_CODE -> keyFile.unlockWithRecoveryCode(secret)
    }
}

/**
 * Seam between the recovery unlock+reset UI and the on-disk envelope, so the commit-ordering
 * invariant — the auth credential ([com.appblish.calculatorvault.auth.CredentialStore.setRealPin])
 * rotates **only after** the envelope has followed the new PIN — stays unit-testable without
 * a device, mirroring [VaultReKeyer] for the change-PIN flow (APP-245). The credential
 * rotation itself lives in the ViewModel; this seam owns only the envelope work.
 */
interface RecoveryReKeyer {
    suspend fun verify(
        method: RecoveryMethod,
        secret: String,
    ): RecoveryVerifyOutcome

    suspend fun resetPin(
        method: RecoveryMethod,
        secret: String,
        newPin: String,
    ): RecoveryResetOutcome
}

/**
 * Device [RecoveryReKeyer] over the real vault's survive-uninstall `.vaultkey` ([namespace]
 * defaults to the root/real vault — recovery only ever rotates the real credential). Refuses
 * to touch the envelope when All Files Access is missing, exactly as the change-PIN re-keyer
 * does, so a reset can never be committed against an unreadable key file.
 */
class VaultKeyFileRecoveryReKeyer(
    context: Context,
    private val namespace: String = "",
) : RecoveryReKeyer {
    private val appContext = context.applicationContext

    private fun keyFile(): VaultKeyFile = VaultKeyFile(VaultStorage.keyFile(appContext, namespace))

    override suspend fun verify(
        method: RecoveryMethod,
        secret: String,
    ): RecoveryVerifyOutcome =
        withContext(Dispatchers.IO) {
            if (!StoragePermissions.hasAllFilesAccess(appContext)) {
                RecoveryVerifyOutcome.STORAGE_UNAVAILABLE
            } else {
                RecoveryEnvelope.verify(keyFile(), method, secret)
            }
        }

    override suspend fun resetPin(
        method: RecoveryMethod,
        secret: String,
        newPin: String,
    ): RecoveryResetOutcome =
        withContext(Dispatchers.IO) {
            if (!StoragePermissions.hasAllFilesAccess(appContext)) {
                RecoveryResetOutcome.STORAGE_UNAVAILABLE
            } else {
                RecoveryEnvelope.resetPin(keyFile(), method, secret, newPin)
            }
        }
}

/**
 * In-memory [RecoveryReKeyer] for `@Preview` / an uninitialised graph: never touches disk.
 * With no configured secret it reports [RecoveryVerifyOutcome.NOT_CONFIGURED] so the screen
 * renders the honest dead-end. Unit tests inject their own recording fake instead.
 */
class InMemoryRecoveryReKeyer(
    private val configured: Boolean = false,
) : RecoveryReKeyer {
    override suspend fun verify(
        method: RecoveryMethod,
        secret: String,
    ): RecoveryVerifyOutcome {
        return if (configured) RecoveryVerifyOutcome.WRONG_SECRET else RecoveryVerifyOutcome.NOT_CONFIGURED
    }

    override suspend fun resetPin(
        method: RecoveryMethod,
        secret: String,
        newPin: String,
    ): RecoveryResetOutcome {
        return if (configured) RecoveryResetOutcome.WRONG_SECRET else RecoveryResetOutcome.NOT_CONFIGURED
    }
}
