package com.appblish.calculatorvault.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecoveryEntryUiState(
    val loading: Boolean = true,
    val configured: Boolean = false,
    val question: String? = null,
)

/**
 * Loads what the recovery-entry landing (W0 08) needs to render: whether recovery is
 * configured for this vault and, if so, the security-question prompt to show as the
 * answer-method subtitle. Pure read — opening this screen resets nothing (spec §1.4).
 */
class RecoveryEntryViewModel(
    private val recoveryManager: RecoveryManager = RecoveryGraph.recoveryManager,
) : ViewModel() {
    constructor() : this(RecoveryGraph.recoveryManager)

    private val _uiState = MutableStateFlow(RecoveryEntryUiState())
    val uiState: StateFlow<RecoveryEntryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val configured = recoveryManager.isConfigured()
            val question = if (configured) recoveryManager.configuredQuestion() else null
            _uiState.update { it.copy(loading = false, configured = configured, question = question) }
        }
    }
}
