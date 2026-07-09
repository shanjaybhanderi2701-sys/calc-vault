package com.appblish.calculatorvault.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.crypto.RecoveryPinReset
import com.appblish.calculatorvault.vault.crypto.RecoveryResetOutcome
import com.appblish.calculatorvault.vault.crypto.RecoveryVerifyOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Steps of the forgot-PIN recovery flow (PIN Recovery W0 09/10 → 11). */
enum class RecoveryUnlockStage {
    /** Enter the security answer or recovery code (Wrap B / Wrap C). */
    VERIFY,

    /** Choose a new PIN. */
    NEW_PIN,

    /** Confirm the new PIN. */
    CONFIRM_PIN,

    /** New PIN committed — Wrap A re-wrapped, credential rotated. */
    DONE,

    /**
     * Recovery can't run here (no key file / no recovery wraps / storage revoked). The honest
     * dead-end (spec §1.5) — never a false "contact support".
     */
    UNAVAILABLE,
}

data class RecoveryUnlockUiState(
    val method: RecoveryMethod,
    val stage: RecoveryUnlockStage = RecoveryUnlockStage.VERIFY,
    val question: String? = null,
    val secretInput: String = "",
    val newPinDraft: String = "",
    val error: String? = null,
    /** Wait still imposed before another verify is allowed (drives the lockout banner). */
    val lockoutRemainingMillis: Long = 0L,
    val verifying: Boolean = false,
)

/**
 * Backs the forgot-PIN recovery flow (W3, spec §5.2): the user proves it's them with the
 * **security answer** (Wrap B) or the **recovery code** (Wrap C), then sets a new PIN which
 * re-wraps **Wrap A only**. Files are never touched — no bulk re-encrypt (§1.3) — and the two
 * recovery wraps are preserved, so both recovery paths keep working after the reset.
 *
 * Wrong answers escalate through [com.appblish.calculatorvault.vault.crypto.RecoveryBackoff];
 * the seam ([RecoveryPinReset]) owns the survive-uninstall counters and reports the lockout.
 * On success the auth credential is rotated **after** the envelope moves, mirroring the
 * change-PIN ordering invariant so a forgotten PIN can never strand the vault.
 */
class RecoveryUnlockViewModel(
    method: RecoveryMethod,
    private val pinReset: RecoveryPinReset = RecoveryGraph.newPinReset(),
    private val recoveryManager: RecoveryManager = RecoveryGraph.recoveryManager,
    private val store: CredentialStore = AuthGraph.credentialStore,
) : ViewModel() {
    private val _state = MutableStateFlow(RecoveryUnlockUiState(method = method))
    val state: StateFlow<RecoveryUnlockUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val question =
                if (method == RecoveryMethod.SECURITY_ANSWER) recoveryManager.configuredQuestion() else null
            val lockout = pinReset.lockoutRemainingMillis(method)
            _state.update { it.copy(question = question, lockoutRemainingMillis = lockout) }
        }
    }

    fun onSecretChanged(value: String) {
        _state.update { it.copy(secretInput = value, error = null) }
    }

    fun onSubmitSecret() {
        val current = _state.value
        if (current.verifying) return
        if (current.secretInput.isBlank()) {
            _state.update { it.copy(error = "Enter your ${it.method.hint()} to continue.") }
            return
        }
        _state.update { it.copy(verifying = true, error = null) }
        viewModelScope.launch {
            when (val outcome = pinReset.verify(current.method, current.secretInput)) {
                RecoveryVerifyOutcome.Verified ->
                    _state.update {
                        it.copy(
                            stage = RecoveryUnlockStage.NEW_PIN,
                            verifying = false,
                            secretInput = "",
                            error = null,
                            lockoutRemainingMillis = 0L
                        )
                    }

                is RecoveryVerifyOutcome.WrongSecret ->
                    _state.update {
                        it.copy(
                            verifying = false,
                            secretInput = "",
                            lockoutRemainingMillis = outcome.remainingLockoutMillis,
                            error = wrongSecretMessage(it.method, outcome.remainingLockoutMillis),
                        )
                    }

                is RecoveryVerifyOutcome.LockedOut ->
                    _state.update {
                        it.copy(
                            verifying = false,
                            secretInput = "",
                            lockoutRemainingMillis = outcome.remainingLockoutMillis,
                            error = lockoutMessage(outcome.remainingLockoutMillis),
                        )
                    }

                RecoveryVerifyOutcome.Unavailable ->
                    _state.update { it.copy(stage = RecoveryUnlockStage.UNAVAILABLE, verifying = false) }
            }
        }
    }

    fun onNewPin(pin: String) {
        _state.update { it.copy(newPinDraft = pin, stage = RecoveryUnlockStage.CONFIRM_PIN, error = null) }
    }

    fun onConfirmPin(pin: String) {
        val draft = _state.value.newPinDraft
        if (pin != draft) {
            _state.update {
                it.copy(stage = RecoveryUnlockStage.NEW_PIN, newPinDraft = "", error = "PINs didn't match. Try again.")
            }
            return
        }
        viewModelScope.launch {
            when (pinReset.resetPin(pin)) {
                RecoveryResetOutcome.RESET -> {
                    // Rotate the auth credential only AFTER the envelope moved to the new PIN —
                    // same ordering invariant as change-PIN, so a forgotten PIN never strands.
                    store.setRealPin(pin)
                    _state.update { it.copy(stage = RecoveryUnlockStage.DONE, newPinDraft = "", error = null) }
                }

                RecoveryResetOutcome.STORAGE_UNAVAILABLE ->
                    _state.update {
                        it.copy(
                            stage = RecoveryUnlockStage.NEW_PIN,
                            newPinDraft = "",
                            error = "Allow “All files access” first — your vault key can't be re-secured without it.",
                        )
                    }

                RecoveryResetOutcome.NOT_VERIFIED ->
                    _state.update {
                        it.copy(
                            stage = RecoveryUnlockStage.VERIFY,
                            newPinDraft = "",
                            error = "Verify your identity again."
                        )
                    }

                RecoveryResetOutcome.FAILED ->
                    _state.update {
                        it.copy(
                            stage = RecoveryUnlockStage.NEW_PIN,
                            newPinDraft = "",
                            error = "Couldn't set your new PIN. Your old PIN is unchanged.",
                        )
                    }
            }
        }
    }

    private fun wrongSecretMessage(
        method: RecoveryMethod,
        lockoutMillis: Long,
    ): String {
        val base = "That ${method.hint()} isn't right."
        return if (lockoutMillis > 0L) "$base ${lockoutMessage(lockoutMillis)}" else "$base Try again."
    }

    private fun lockoutMessage(lockoutMillis: Long): String {
        val seconds = (lockoutMillis + 999L) / 1000L
        return if (seconds >= 60L) {
            "Too many tries — wait ${(seconds + 59L) / 60L} min before trying again."
        } else {
            "Too many tries — wait $seconds s before trying again."
        }
    }

    private fun RecoveryMethod.hint(): String =
        when (this) {
            RecoveryMethod.SECURITY_ANSWER -> "answer"
            RecoveryMethod.RECOVERY_CODE -> "recovery code"
        }

    companion object {
        /** Factory so the screen can pass the route's [method] into the VM (Compose `viewModel()`). */
        fun factory(method: RecoveryMethod): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = RecoveryUnlockViewModel(method) as T
            }
    }
}
