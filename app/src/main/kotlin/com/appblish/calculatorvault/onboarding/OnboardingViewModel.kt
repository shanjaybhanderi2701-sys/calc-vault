package com.appblish.calculatorvault.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.auth.RecoverySetup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The ordered steps of the first-run wizard, matching the board's App Starting Flow. */
enum class OnboardingStep {
    LANGUAGE,
    ALLOW_FILE_ACCESS,
    CREATE_PIN,
    CONFIRM_PIN,
    SECURITY_QUESTION,
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
 * Drives the onboarding wizard: language → allow-file-access → create PIN → confirm PIN →
 * security question → the two intro cards. The real PIN is persisted the moment the
 * confirm matches the draft; recovery material is persisted on the security-question step;
 * onboarding is flagged complete only when the final intro card is dismissed. All writes
 * go through the injected [CredentialStore].
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

    fun onLanguageDone() = goTo(OnboardingStep.ALLOW_FILE_ACCESS)

    /** Both "Allow" and "Not Now" advance; the actual permission grant is the screen's job. */
    fun onFileAccessHandled() = goTo(OnboardingStep.CREATE_PIN)

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
            _state.update { it.copy(mismatch = false, step = OnboardingStep.SECURITY_QUESTION) }
        }
    }

    fun onSecuritySaved(setup: RecoverySetup) {
        viewModelScope.launch {
            store.setRecovery(setup)
            goTo(OnboardingStep.INTRO_PRIVATE)
        }
    }

    /** Cancel on the security modal is allowed — recovery can be set later in Settings. */
    fun onSecuritySkipped() = goTo(OnboardingStep.INTRO_PRIVATE)

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
                OnboardingStep.ALLOW_FILE_ACCESS -> OnboardingStep.LANGUAGE
                OnboardingStep.CREATE_PIN -> OnboardingStep.ALLOW_FILE_ACCESS
                OnboardingStep.CONFIRM_PIN -> OnboardingStep.CREATE_PIN
                OnboardingStep.SECURITY_QUESTION -> OnboardingStep.CONFIRM_PIN
                OnboardingStep.INTRO_PRIVATE -> OnboardingStep.SECURITY_QUESTION
                OnboardingStep.INTRO_ICONS -> OnboardingStep.INTRO_PRIVATE
            }
        goTo(previous)
    }

    private fun goTo(step: OnboardingStep) {
        _state.update { it.copy(step = step) }
    }
}
