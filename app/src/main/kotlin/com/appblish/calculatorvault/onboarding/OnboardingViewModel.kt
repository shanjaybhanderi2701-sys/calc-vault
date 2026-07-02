package com.appblish.calculatorvault.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ordered steps of the first-run wizard. xlock parity (APP-212): the wizard is a clean
 * calculator/PIN setup only — no upfront permission wall and no security-question step.
 * All Files Access is primed at first vault open, accessibility at first App-Lock enable,
 * and the recovery security question is deferred until *after* the first real vault
 * operation (see [com.appblish.calculatorvault.navigation.DeferredRecoveryPrompt]).
 */
enum class OnboardingStep {
    LANGUAGE,
    CREATE_PIN,
    CONFIRM_PIN,
    INTRO_PRIVATE,
    INTRO_ICONS,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.LANGUAGE,
    val language: String = DEFAULT_LANGUAGE,
    val pinDraft: String = "",
    val mismatch: Boolean = false,
    val finished: Boolean = false,
)

/**
 * Drives the onboarding wizard: language → create PIN → confirm PIN → the two intro cards.
 * The real PIN is persisted the moment the confirm matches the draft; onboarding is flagged
 * complete when the final intro card is dismissed. There is deliberately no permission wall
 * or security-question step here — both are deferred to first use (APP-212). All writes go
 * through the injected [CredentialStore].
 */
class OnboardingViewModel(
    private val store: CredentialStore,
) : ViewModel() {
    constructor() : this(AuthGraph.credentialStore)

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun selectLanguage(language: String) {
        _state.update { it.copy(language = language) }
    }

    fun onLanguageDone() = goTo(OnboardingStep.CREATE_PIN)

    fun onPinCreated(pin: String) {
        _state.update { it.copy(pinDraft = pin, mismatch = false, step = OnboardingStep.CONFIRM_PIN) }
    }

    fun onPinConfirmed(pin: String) {
        if (pin != _state.value.pinDraft) {
            // Mismatch: bounce back to create, clear the draft, surface the error.
            _state.update { it.copy(pinDraft = "", mismatch = true, step = OnboardingStep.CREATE_PIN) }
            return
        }
        viewModelScope.launch {
            store.setRealPin(pin)
            // Straight to the intro cards — the security question is deferred to first use.
            _state.update { it.copy(mismatch = false, step = OnboardingStep.INTRO_PRIVATE) }
        }
    }

    fun onIntroNext() = goTo(OnboardingStep.INTRO_ICONS)

    fun onFinish() {
        viewModelScope.launch {
            store.completeOnboarding()
            _state.update { it.copy(finished = true) }
        }
    }

    /** Back navigation within the wizard; no-op past the first step (host handles system back). */
    fun onBack() {
        val previous =
            when (_state.value.step) {
                OnboardingStep.LANGUAGE -> return
                OnboardingStep.CREATE_PIN -> OnboardingStep.LANGUAGE
                OnboardingStep.CONFIRM_PIN -> OnboardingStep.CREATE_PIN
                // Past PIN creation the intro cards are informational; back goes no further
                // than re-confirming the PIN.
                OnboardingStep.INTRO_PRIVATE -> OnboardingStep.CONFIRM_PIN
                OnboardingStep.INTRO_ICONS -> OnboardingStep.INTRO_PRIVATE
            }
        goTo(previous)
    }

    private fun goTo(step: OnboardingStep) {
        _state.update { it.copy(step = step) }
    }
}
