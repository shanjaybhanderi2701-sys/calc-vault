package com.appblish.calculatorvault.calculator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.VaultKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the calculator disguise surface. */
data class CalculatorUiState(
    val display: String = "",
    val unlock: VaultKind? = null,
)

/**
 * Drives the disguise. Every keypress just edits the display like a normal calculator;
 * the only special key is `=`. On `=`, if the shown value is a 4-digit code that
 * [resolvePin] maps to a vault (the real one, or a decoy), we raise
 * [CalculatorUiState.unlock] so the host can open that vault. Anything else is evaluated
 * as ordinary arithmetic via [CalculatorEngine] — so to an onlooker it is only ever a
 * calculator.
 *
 * [resolvePin] is injected (defaults to the app's [AuthGraph] credential store) so the
 * resolution rule is unit-testable without Android.
 */
class CalculatorViewModel(
    private val engine: CalculatorEngine = CalculatorEngine,
    private val resolvePin: suspend (String) -> VaultKind? = { AuthGraph.credentialStore.resolve(it) },
) : ViewModel() {
    // Zero-arg constructor for the default Compose `viewModel()` factory.
    constructor() : this(CalculatorEngine)

    private val _uiState = MutableStateFlow(CalculatorUiState())
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()

    fun onToken(token: CalcToken) {
        when (token) {
            CalcToken.CLEAR -> _uiState.value = CalculatorUiState()
            CalcToken.BACKSPACE -> _uiState.update { it.copy(display = it.display.dropLast(1)) }
            CalcToken.EQUALS -> onEquals()
            else -> token.input?.let { ch -> _uiState.update { it.copy(display = it.display + ch) } }
        }
    }

    private fun onEquals() {
        val current = _uiState.value.display
        viewModelScope.launch {
            if (current.isPinCandidate()) {
                val kind = resolvePin(current)
                if (kind != null) {
                    _uiState.update { it.copy(unlock = kind) }
                    return@launch
                }
            }
            val value = engine.evaluate(current)
            _uiState.update { state -> state.copy(display = value?.let(::formatResult) ?: state.display) }
        }
    }

    fun onUnlockHandled() {
        _uiState.update { it.copy(unlock = null) }
    }

    private fun String.isPinCandidate(): Boolean = length == PIN_LENGTH && all(Char::isDigit)

    private fun formatResult(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
