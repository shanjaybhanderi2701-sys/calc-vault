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
    // Which vault the typed code opens (real or a decoy), or null while it is just a
    // calculator. Retained for UI labeling of the opened space.
    val unlock: VaultKind? = null,
    // The code that resolved to [unlock]. Forwarded as the vault passphrase so the storage
    // layer can derive the data key that unwraps `.CalcVault/` (see VaultKeyFile /
    // VaultSession). Captured at the moment `=` matches a configured code.
    val unlockCode: String = "",
    // Bumped each time a 4-digit entry fails to resolve to any vault on `=`. The UI keys
    // a brief display shake off changes to this value — the ONLY wrong-PIN feedback.
    // Nothing disguise-breaking (dialog/banner/toast) is ever shown (APP-242).
    val pinRejections: Int = 0,
)

/**
 * Drives the disguise. Every keypress just edits the display like a normal calculator;
 * the only special key is `=`. On `=`, if the shown value is a 4-digit code that
 * [resolvePin] maps to a vault (the real one, or a decoy), we raise
 * [CalculatorUiState.unlock] (carrying the code) so the host can open that vault. A
 * 4-digit code that resolves to nothing clears the display and bumps
 * [CalculatorUiState.pinRejections] (the UI shakes the display — minimal wrong-PIN
 * feedback, APP-242). Anything else is evaluated as ordinary arithmetic via
 * [CalculatorEngine] — so to an onlooker it is only ever a calculator.
 *
 * [resolvePin] is injected (defaults to the app's [AuthGraph] credential store) so the
 * resolution rule is unit-testable without Android. There is no debug backdoor: only a
 * code persisted by onboarding/settings resolves to a vault (spec §11, APP-225).
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
                    _uiState.update { it.copy(unlock = kind, unlockCode = current) }
                } else {
                    // Wrong PIN: clear the digits and shake the display, staying on the
                    // calculator. Deliberately no lockout/backoff/intruder capture —
                    // deferred by the board past Phase 1 (APP-242).
                    _uiState.update { it.copy(display = "", pinRejections = it.pinRejections + 1) }
                }
                return@launch
            }
            val value = engine.evaluate(current)
            _uiState.update { state -> state.copy(display = value?.let(::formatResult) ?: state.display) }
        }
    }

    fun onUnlockHandled() {
        _uiState.update { it.copy(unlock = null, unlockCode = "") }
    }

    private fun String.isPinCandidate(): Boolean = length == PIN_LENGTH && all(Char::isDigit)

    private fun formatResult(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
