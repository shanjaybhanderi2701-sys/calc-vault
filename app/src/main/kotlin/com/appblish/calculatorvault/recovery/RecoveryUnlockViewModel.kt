package com.appblish.calculatorvault.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.crypto.RecoveryBackoff
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.crypto.RecoveryReKeyer
import com.appblish.calculatorvault.vault.crypto.RecoveryResetOutcome
import com.appblish.calculatorvault.vault.crypto.RecoveryVerifyOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Steps of the recovery unlock → set-new-PIN flow (W0 09/10 → 11). */
enum class RecoveryUnlockStage {
    /** Enter the security answer (Wrap B) or recovery code (Wrap C) to prove identity. */
    ENTER_SECRET,

    /** Choose a new PIN. */
    NEW_PIN,

    /** Re-enter the new PIN to confirm. */
    CONFIRM_PIN,

    /** Reset complete — the vault is unlocked under the new PIN. */
    DONE,

    /** Recovery was never configured — honest dead-end, nothing to reset from (spec §1.5). */
    UNRECOVERABLE,
}

data class RecoveryUnlockUiState(
    val method: RecoveryMethod,
    val stage: RecoveryUnlockStage = RecoveryUnlockStage.ENTER_SECRET,
    val question: String? = null,
    val error: String? = null,
    val lockoutRemainingMillis: Long = 0L,
    val busy: Boolean = false,
) {
    val lockedOut: Boolean get() = lockoutRemainingMillis > 0L
}

/**
 * Backs the recovery unlock + PIN reset flow (APP-325 W3). The user proves identity with the
 * security answer or the recovery code, then sets a new PIN; the vault's data key is unwrapped
 * via Wrap B/C and **only Wrap A** is re-created under the new PIN (spec §1.3 / §5.2) — the
 * files are never touched and the other recovery paths keep working.
 *
 * **Commit-ordering invariant (inherited from APP-245).** [RecoveryReKeyer.resetPin] moves the
 * `.vaultkey` envelope first; the auth credential ([CredentialStore.setRealPin]) is committed
 * **only** after that succeeds, so a failed reset can never diverge the token from the envelope
 * and strand the vault.
 *
 * **Backoff (spec §1.6).** Each wrong secret records a failure in the survive-uninstall
 * [RecoveryAttemptStore]; [RecoveryBackoff] turns a guessing spree into an escalating lockout.
 * A correct secret / successful reset clears the streak.
 */
class RecoveryUnlockViewModel(
    private val method: RecoveryMethod,
    private val reKeyer: RecoveryReKeyer = RecoveryGraph.recoveryReKeyer,
    private val recoveryManager: RecoveryManager = RecoveryGraph.recoveryManager,
    private val credentialStore: CredentialStore = AuthGraph.credentialStore,
    private val attemptStore: RecoveryAttemptStore = RecoveryGraph.recoveryAttemptStore,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(RecoveryUnlockUiState(method = method))
    val state: StateFlow<RecoveryUnlockUiState> = _state.asStateFlow()

    // The verified secret, held in memory only for the life of this flow so the confirm step
    // can re-wrap without re-prompting. Never exposed through the UI state; cleared on success.
    private var verifiedSecret: String? = null
    private var newPinDraft: String? = null

    init {
        viewModelScope.launch {
            val configured = recoveryManager.isConfigured()
            if (!configured) {
                _state.update { it.copy(stage = RecoveryUnlockStage.UNRECOVERABLE) }
            } else {
                val question =
                    if (method == RecoveryMethod.SECURITY_ANSWER) recoveryManager.configuredQuestion() else null
                _state.update {
                    it.copy(question = question, lockoutRemainingMillis = remainingLockout())
                }
            }
        }
    }

    private fun remainingLockout(): Long =
        RecoveryBackoff.remainingLockoutMillis(
            failedAttempts = attemptStore.failedAttempts(method),
            lastFailureAtMillis = attemptStore.lastFailureAtMillis(method),
            nowMillis = now(),
        )

    fun onSubmitSecret(secret: String) {
        val remaining = remainingLockout()
        if (remaining > 0L) {
            _state.update { it.copy(lockoutRemainingMillis = remaining, error = null) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (reKeyer.verify(method, secret)) {
                    RecoveryVerifyOutcome.CORRECT -> {
                        attemptStore.clear(method)
                        verifiedSecret = secret
                        _state.update {
                            it.copy(
                                stage = RecoveryUnlockStage.NEW_PIN,
                                error = null,
                                lockoutRemainingMillis = 0L,
                                busy = false,
                            )
                        }
                    }

                    RecoveryVerifyOutcome.WRONG_SECRET -> {
                        attemptStore.recordFailure(method, now())
                        _state.update {
                            it.copy(
                                error = wrongSecretMessage(),
                                lockoutRemainingMillis = remainingLockout(),
                                busy = false
                            )
                        }
                    }

                    RecoveryVerifyOutcome.NOT_CONFIGURED ->
                        _state.update { it.copy(stage = RecoveryUnlockStage.UNRECOVERABLE, busy = false) }

                    RecoveryVerifyOutcome.STORAGE_UNAVAILABLE ->
                        _state.update { it.copy(error = STORAGE_MESSAGE, busy = false) }
                }
            } catch (e: Exception) {
                // Fail closed: a malformed key file or a failed atomic backoff write
                // (recordFailure/clear) must surface the honest error and release the spinner —
                // never cancel the coroutine and strand busy=true (APP-331 O1). The on-disk
                // attempt counter is untouched by a throw, so the lockout is not weakened.
                _state.update { it.copy(error = STORAGE_MESSAGE, busy = false) }
            }
        }
    }

    fun onNewPin(pin: String) {
        newPinDraft = pin
        _state.update { it.copy(stage = RecoveryUnlockStage.CONFIRM_PIN, error = null) }
    }

    fun onConfirmPin(pin: String) {
        if (pin != newPinDraft) {
            newPinDraft = null
            _state.update {
                it.copy(stage = RecoveryUnlockStage.NEW_PIN, error = "PINs didn't match. Try again.")
            }
            return
        }
        val secret = verifiedSecret
        if (secret == null) {
            _state.update {
                it.copy(stage = RecoveryUnlockStage.ENTER_SECRET, error = "Let's verify it's you again.")
            }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (reKeyer.resetPin(method, secret, pin)) {
                    RecoveryResetOutcome.RESET -> {
                        // Envelope moved first (above); only now rotate the auth credential and the
                        // live session so the new PIN both authenticates and unwraps the vault.
                        credentialStore.setRealPin(pin)
                        VaultSession.begin(pin)
                        attemptStore.clear(method)
                        verifiedSecret = null
                        newPinDraft = null
                        _state.update { it.copy(stage = RecoveryUnlockStage.DONE, error = null, busy = false) }
                    }

                    RecoveryResetOutcome.WRONG_SECRET -> {
                        attemptStore.recordFailure(method, now())
                        verifiedSecret = null
                        _state.update {
                            it.copy(
                                stage = RecoveryUnlockStage.ENTER_SECRET,
                                error = wrongSecretMessage(),
                                lockoutRemainingMillis = remainingLockout(),
                                busy = false,
                            )
                        }
                    }

                    RecoveryResetOutcome.NOT_CONFIGURED ->
                        _state.update { it.copy(stage = RecoveryUnlockStage.UNRECOVERABLE, busy = false) }

                    RecoveryResetOutcome.STORAGE_UNAVAILABLE ->
                        _state.update {
                            it.copy(stage = RecoveryUnlockStage.NEW_PIN, error = STORAGE_MESSAGE, busy = false)
                        }

                    RecoveryResetOutcome.FAILED ->
                        _state.update {
                            it.copy(
                                stage = RecoveryUnlockStage.NEW_PIN,
                                error = "Couldn't set your new PIN. Your vault is unchanged — please try again.",
                                busy = false,
                            )
                        }
                }
            } catch (e: Exception) {
                // Fail closed like onSubmitSecret: an unexpected throw (backoff write, credential
                // rotation) must land on a recoverable error with the spinner released, never a
                // stuck busy=true (APP-331 O1). The commit-ordering invariant already guarantees the
                // envelope moved before setRealPin, so the vault is never left half-reset.
                _state.update {
                    it.copy(
                        stage = RecoveryUnlockStage.NEW_PIN,
                        error = "Couldn't set your new PIN. Your vault is unchanged — please try again.",
                        busy = false,
                    )
                }
            }
        }
    }

    private fun wrongSecretMessage(): String =
        when (method) {
            RecoveryMethod.SECURITY_ANSWER -> "That answer doesn't match. Try again."
            RecoveryMethod.RECOVERY_CODE -> "That code doesn't match. Check it and try again."
        }

    private companion object {
        const val STORAGE_MESSAGE =
            "Allow “All files access” first — your vault key can't be reached without it."
    }
}
