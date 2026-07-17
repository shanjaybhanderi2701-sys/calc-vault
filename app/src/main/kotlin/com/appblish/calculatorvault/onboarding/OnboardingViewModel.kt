package com.appblish.calculatorvault.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.settings.SettingsGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ordered steps of the first-run wizard. Phase 1 (build spec §1) is a clean
 * calculator/PIN setup only — no upfront permission wall, no recovery step of any kind, and
 * no intro carousel: onboarding goes straight to language → PIN, then lands in the vault.
 * (APP-528: the 2-page intro viewpager was removed.) Per build spec §0, PIN recovery is
 * deferred to a later phase entirely (accepted risk). All Files Access is primed at first
 * vault open and accessibility at first App-Lock enable.
 */
enum class OnboardingStep {
    LANGUAGE,
    CREATE_PIN,
    CONFIRM_PIN,
}

/** Minimum time the "Setting Up Language" loader (S3) stays visible after Done is tapped. */
internal const val LANGUAGE_APPLY_MILLIS = 600L

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.LANGUAGE,
    val language: String = DEFAULT_LANGUAGE,
    /** True while the S3 "Setting Up Language" loader overlays the language list. */
    val applyingLanguage: Boolean = false,
    val pinDraft: String = "",
    val mismatch: Boolean = false,
    val finished: Boolean = false,
)

/**
 * Drives the onboarding wizard: language → create PIN → confirm PIN. The real PIN is
 * persisted the moment the confirm matches the draft, and onboarding is flagged complete in
 * the same step so the host lands straight in the vault (APP-528: no intro carousel). There
 * is deliberately no permission wall and no recovery step here — permissions are primed at
 * first use, and recovery does not
 * exist anywhere in Phase 1 (build spec §0: deferred to a later phase, accepted risk).
 * Credential writes go through the injected [CredentialStore]; the chosen language is
 * persisted fire-and-forget via [saveLanguage] (defaults to the [SettingsGraph] store).
 */
class OnboardingViewModel(
    private val store: CredentialStore,
    private val saveLanguage: suspend (String) -> Unit = ::persistLanguageToSettings,
) : ViewModel() {
    constructor() : this(AuthGraph.credentialStore)

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun selectLanguage(language: String) {
        _state.update { it.copy(language = language) }
    }

    /**
     * Done on the language step: persist the choice fire-and-forget, then hold the S3
     * "Setting Up Language" loader for [LANGUAGE_APPLY_MILLIS] before advancing to PIN
     * creation. The loader is a fixed dwell — it never waits on the save, so a slow or
     * failing settings store cannot stall the wizard.
     */
    fun onLanguageDone() {
        if (_state.value.applyingLanguage) return
        val language = _state.value.language
        _state.update { it.copy(applyingLanguage = true) }
        viewModelScope.launch { runCatching { saveLanguage(language) } }
        viewModelScope.launch {
            delay(LANGUAGE_APPLY_MILLIS)
            _state.update {
                // Only advance if the wizard is still on the language step, so a stale
                // loader can never yank the user backwards; either way the flag clears,
                // guaranteeing back-navigation cannot get stuck on the loader.
                if (it.step == OnboardingStep.LANGUAGE) {
                    it.copy(applyingLanguage = false, step = OnboardingStep.CREATE_PIN)
                } else {
                    it.copy(applyingLanguage = false)
                }
            }
        }
    }

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
            // Phase 1 has no recovery step and no intro carousel (APP-528): persisting the
            // PIN completes onboarding outright, and the host opens the vault directly.
            store.completeOnboarding()
            _state.update { it.copy(mismatch = false, finished = true) }
        }
    }

    /** Back navigation within the wizard; no-op past the first step (host handles system back). */
    fun onBack() {
        val previous =
            when (_state.value.step) {
                OnboardingStep.LANGUAGE -> return
                OnboardingStep.CREATE_PIN -> OnboardingStep.LANGUAGE
                OnboardingStep.CONFIRM_PIN -> OnboardingStep.CREATE_PIN
            }
        goTo(previous)
    }

    private fun goTo(step: OnboardingStep) {
        _state.update { it.copy(step = step) }
    }
}

/**
 * Default language persistence: write [language] into [SettingsGraph.settingsStore]. Wrapped
 * in [runCatching] because the locator throws before `SettingsGraph.init` — unit tests that
 * exercise the wizard without wiring the settings store must not crash, and a failed save is
 * cosmetic (Settings falls back to "Default").
 */
private suspend fun persistLanguageToSettings(language: String) {
    runCatching {
        val settingsStore = SettingsGraph.settingsStore
        settingsStore.save(settingsStore.load().copy(appLanguage = language))
    }
}
