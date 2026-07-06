package com.appblish.calculatorvault.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.crypto.ReKeyOutcome
import com.appblish.calculatorvault.vault.crypto.VaultReKeyer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Steps of the change-unlock-PIN flow. */
enum class ChangePinStage {
    VERIFY,
    NEW,
    CONFIRM,
    DONE,
}

data class ChangePinUiState(
    val stage: ChangePinStage = ChangePinStage.VERIFY,
    val draft: String = "",
    val error: String? = null,
)

/**
 * Backs Settings → Change unlock PIN. First proves the user is the owner by requiring the
 * *current* real PIN (a decoy PIN must never be able to change the real credential), then
 * captures and confirms the new one. All entry reuses the same calculator PIN surface as
 * onboarding so the flow stays disguised.
 *
 * **Data-safety invariant (APP-245).** The vault's data key lives on disk wrapped under a
 * KEK derived from the PIN (`.vaultkey`, [com.appblish.calculatorvault.vault.crypto.VaultKeyFile]),
 * so the auth token and the key envelope must rotate *together*: committing
 * [CredentialStore.setRealPin] without re-wrapping strands the whole vault (the new PIN
 * passes auth but fails the GCM unwrap → permanently unreadable if the old PIN is
 * forgotten). [onConfirm] therefore asks [VaultReKeyer] to move the envelope first and
 * commits the credential **only** on [ReKeyOutcome.REWRAPPED] (or [ReKeyOutcome.NO_KEY_FILE]
 * — nothing hidden yet, nothing to move). When storage is unreachable or the re-wrap fails,
 * the change is refused and the old PIN stays in force — never a token/envelope divergence.
 */
class ChangePinViewModel(
    private val store: CredentialStore,
    private val reKeyer: VaultReKeyer,
) : ViewModel() {
    constructor() : this(AuthGraph.credentialStore, AuthGraph.vaultReKeyer)

    private val _state = MutableStateFlow(ChangePinUiState())
    val state: StateFlow<ChangePinUiState> = _state.asStateFlow()

    // The verified current PIN, held in memory only for the life of this flow: rewrap needs
    // it to unwrap the DEK. Never exposed through the UI state.
    private var verifiedCurrentPin: String? = null

    fun onVerify(pin: String) {
        viewModelScope.launch {
            if (store.resolve(pin) == VaultKind.Real) {
                verifiedCurrentPin = pin
                _state.update { it.copy(stage = ChangePinStage.NEW, error = null) }
            } else {
                _state.update { it.copy(error = "That's not your current password.") }
            }
        }
    }

    fun onNew(pin: String) {
        _state.update { it.copy(draft = pin, stage = ChangePinStage.CONFIRM, error = null) }
    }

    fun onConfirm(pin: String) {
        if (pin != _state.value.draft) {
            _state.update { it.copy(stage = ChangePinStage.NEW, draft = "", error = "PINs didn't match. Try again.") }
            return
        }
        val currentPin = verifiedCurrentPin
        if (currentPin == null) {
            _state.update { ChangePinUiState(error = "Verify your current password first.") }
            return
        }
        viewModelScope.launch {
            when (reKeyer.rewrap(currentPin, pin)) {
                ReKeyOutcome.REWRAPPED, ReKeyOutcome.NO_KEY_FILE -> {
                    store.setRealPin(pin)
                    // Keep the live session in step: it still holds the old PIN, and a later
                    // unlock() in this session would derive the old KEK against the freshly
                    // re-wrapped key file and fail. Only the real (root-namespace) session
                    // rotates — decoy envelopes are untouched by a real-PIN change.
                    if (VaultSession.passphrase == currentPin && VaultSession.namespace.isEmpty()) {
                        VaultSession.begin(pin)
                    }
                    verifiedCurrentPin = null
                    _state.update { it.copy(stage = ChangePinStage.DONE, draft = "", error = null) }
                }

                ReKeyOutcome.STORAGE_UNAVAILABLE ->
                    _state.update {
                        it.copy(
                            stage = ChangePinStage.NEW,
                            draft = "",
                            error =
                                "Allow “All files access” first — your vault key can't be " +
                                    "re-secured without it. Password unchanged.",
                        )
                    }

                ReKeyOutcome.FAILED ->
                    _state.update {
                        it.copy(
                            stage = ChangePinStage.NEW,
                            draft = "",
                            error = "Couldn't re-secure your vault key, so your password was not changed.",
                        )
                    }
            }
        }
    }
}
