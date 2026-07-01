package com.appblish.calculatorvault.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.auth.RecoveryInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the forgot-password flow is: prove identity, set a new PIN, done. */
enum class RecoveryStage {
    VERIFY,
    RESET,
    DONE,
}

data class RecoveryUiState(
    val loaded: Boolean = false,
    val info: RecoveryInfo? = null,
    val stage: RecoveryStage = RecoveryStage.VERIFY,
    val wrongAnswer: Boolean = false,
)

/**
 * Backs the forgot-password / reset flow. It loads the (non-secret) recovery material to
 * show the security question, email, and hint; verifies a typed answer against the stored
 * hash; and, once verified, sets a brand-new real PIN on the calculator. If no recovery
 * was ever configured, [RecoveryUiState.info] stays null and the UI explains that.
 */
class RecoveryViewModel(
    private val store: CredentialStore,
) : ViewModel() {
    constructor() : this(AuthGraph.credentialStore)

    private val _state = MutableStateFlow(RecoveryUiState())
    val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val info = store.recoveryInfo()
            _state.update { it.copy(loaded = true, info = info) }
        }
    }

    fun verifyAnswer(answer: String) {
        viewModelScope.launch {
            if (store.verifyRecoveryAnswer(answer)) {
                _state.update { it.copy(stage = RecoveryStage.RESET, wrongAnswer = false) }
            } else {
                _state.update { it.copy(wrongAnswer = true) }
            }
        }
    }

    fun resetPin(newPin: String) {
        viewModelScope.launch {
            store.setRealPin(newPin)
            _state.update { it.copy(stage = RecoveryStage.DONE) }
        }
    }
}
