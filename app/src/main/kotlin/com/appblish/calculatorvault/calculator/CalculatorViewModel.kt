package com.appblish.calculatorvault.calculator

import androidx.lifecycle.ViewModel
import com.appblish.calculatorvault.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI state for the calculator disguise surface. */
data class CalculatorUiState(
    val input: String = "",
    val result: String = "",
    val unlockRequested: Boolean = false,
)

/**
 * Drives the calculator disguise. Pressing '=' either shows a normal result or, when
 * the input matches the secret code, raises [CalculatorUiState.unlockRequested] so the
 * host can navigate to PIN/vault entry. All arithmetic is delegated to
 * [CalculatorEngine] and all unlock logic to [SecretCodeDetector], keeping this class
 * a thin, testable state holder.
 */
class CalculatorViewModel(
    private val engine: CalculatorEngine = CalculatorEngine,
    private val secretDetector: SecretCodeDetector = SecretCodeDetector(secretProvider = { defaultSecret() }),
) : ViewModel() {
    // Zero-arg constructor so the default Compose `viewModel()` factory (which needs a
    // no-arg JVM constructor) can instantiate this; the primary ctor stays open for tests.
    constructor() : this(CalculatorEngine, SecretCodeDetector(secretProvider = { defaultSecret() }))

    private val _uiState = MutableStateFlow(CalculatorUiState())
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()

    fun onDigit(token: String) {
        _uiState.value = _uiState.value.copy(input = _uiState.value.input + token, unlockRequested = false)
    }

    fun onClear() {
        _uiState.value = CalculatorUiState()
    }

    fun onEquals() {
        val current = _uiState.value.input
        if (secretDetector.isUnlockTrigger(current)) {
            _uiState.value = _uiState.value.copy(unlockRequested = true)
            return
        }
        val value = engine.evaluate(current)
        _uiState.value =
            _uiState.value.copy(
                result = value?.let { formatResult(it) } ?: "",
            )
    }

    fun onUnlockHandled() {
        _uiState.value = _uiState.value.copy(unlockRequested = false)
    }

    private fun formatResult(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private companion object {
        // Debug/dev builds ship a fixed unlock code ("1234=") so the vault is reachable on
        // an emulator before the Phase 1 onboarding (which persists the real user secret)
        // is merged into this branch. Release builds return null — no default backdoor.
        fun defaultSecret(): String? = if (BuildConfig.DEBUG) "1234" else null
    }
}
