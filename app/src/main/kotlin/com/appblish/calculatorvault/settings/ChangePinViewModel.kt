package com.appblish.calculatorvault.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.auth.VaultKind
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
 * captures and confirms the new one before committing through [CredentialStore.setRealPin].
 * All entry reuses the same calculator PIN surface as onboarding so the flow stays disguised.
 */
class ChangePinViewModel(
    private val store: CredentialStore,
) : ViewModel() {
    constructor() : this(AuthGraph.credentialStore)

    private val _state = MutableStateFlow(ChangePinUiState())
    val state: StateFlow<ChangePinUiState> = _state.asStateFlow()

    fun onVerify(pin: String) {
        viewModelScope.launch {
            if (store.resolve(pin) == VaultKind.Real) {
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
        viewModelScope.launch {
            store.setRealPin(pin)
            _state.update { it.copy(stage = ChangePinStage.DONE, draft = "", error = null) }
        }
    }
}
