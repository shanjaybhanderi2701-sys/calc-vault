package com.appblish.calculatorvault.fakepassword

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the Fake Password screen is showing the list or capturing a new decoy PIN. */
enum class FakeStage {
    LIST,
    CREATE,
    CONFIRM,
}

data class FakePasswordUiState(
    val slots: List<Int> = emptyList(),
    val stage: FakeStage = FakeStage.LIST,
    val draft: String = "",
    val error: String? = null,
)

/**
 * Backs the Fake Password (duress) manager. Each configured decoy PIN opens its own
 * separate decoy vault; the add flow captures and confirms a 4-digit code on the
 * calculator and hands it to [CredentialStore.addDecoyPin], which rejects a code that
 * collides with the real PIN or an existing decoy.
 */
class FakePasswordViewModel(
    private val store: CredentialStore,
) : ViewModel() {
    constructor() : this(AuthGraph.credentialStore)

    private val _state = MutableStateFlow(FakePasswordUiState())
    val state: StateFlow<FakePasswordUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun startAdd() {
        _state.update { it.copy(stage = FakeStage.CREATE, draft = "", error = null) }
    }

    fun cancelAdd() {
        _state.update { it.copy(stage = FakeStage.LIST, draft = "", error = null) }
    }

    fun onCreated(pin: String) {
        _state.update { it.copy(draft = pin, stage = FakeStage.CONFIRM, error = null) }
    }

    fun onConfirmed(pin: String) {
        if (pin != _state.value.draft) {
            _state.update { it.copy(stage = FakeStage.CREATE, draft = "", error = "PINs didn't match. Try again.") }
            return
        }
        viewModelScope.launch {
            val slot = store.addDecoyPin(pin)
            if (slot == null) {
                _state.update {
                    it.copy(stage = FakeStage.CREATE, draft = "", error = "That PIN is already in use. Pick another.")
                }
            } else {
                _state.update { it.copy(stage = FakeStage.LIST, draft = "", error = null, slots = decoyList()) }
            }
        }
    }

    fun remove(slot: Int) {
        viewModelScope.launch {
            store.removeDecoy(slot)
            _state.update { it.copy(slots = decoyList()) }
        }
    }

    private fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(slots = decoyList()) }
        }
    }

    private suspend fun decoyList(): List<Int> = store.decoySlots()
}
