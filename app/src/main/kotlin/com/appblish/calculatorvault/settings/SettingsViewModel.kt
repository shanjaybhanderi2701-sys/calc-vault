package com.appblish.calculatorvault.settings

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

/**
 * State for the Settings surface: the persisted [VaultSettings] plus a little derived
 * credential context (how many decoy PINs exist, whether recovery is configured) shown as
 * row subtitles.
 */
data class SettingsUiState(
    val settings: VaultSettings = VaultSettings(),
    val decoyCount: Int = 0,
    val hasRecovery: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Backs the Settings, Theme, and hardening toggle screens. Owns the single source of truth
 * for [VaultSettings]; every setter persists through [SettingsStore] and optimistically
 * updates the flow so the switches feel instant. The OS-level side effects of the
 * prevent-uninstall and disguise switches happen in the screen (they need an Activity /
 * PackageManager); this VM only records the resulting boolean.
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val credentialStore: CredentialStore,
) : ViewModel() {
    constructor() : this(SettingsGraph.settingsStore, AuthGraph.credentialStore)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.load()
            _state.update {
                it.copy(
                    settings = settings,
                    decoyCount = credentialStore.decoySlots().size,
                    hasRecovery = credentialStore.recoveryInfo() != null,
                    loaded = true,
                )
            }
        }
    }

    fun setKeypadSkin(skin: KeypadSkin) = mutate { it.copy(keypadSkin = skin) }

    fun setUnlockAnimation(animation: UnlockAnimation) = mutate { it.copy(unlockAnimation = animation) }

    fun setBreakInAlerts(enabled: Boolean) = mutate { it.copy(breakInAlertsEnabled = enabled) }

    fun setFakePassword(enabled: Boolean) = mutate { it.copy(fakePasswordEnabled = enabled) }

    fun setRelockOnBackground(enabled: Boolean) = mutate { it.copy(relockOnBackgroundEnabled = enabled) }

    fun setShufflePinPad(enabled: Boolean) = mutate { it.copy(shufflePinPadEnabled = enabled) }

    fun setIncorrectVibration(enabled: Boolean) = mutate { it.copy(incorrectVibrationEnabled = enabled) }

    /** Record the prevent-uninstall result after the OS consent flow resolves. */
    fun setPreventUninstall(enabled: Boolean) = mutate { it.copy(preventUninstallEnabled = enabled) }

    /** Record the disguise result after the launcher alias has been swapped. */
    fun setDisguiseIcon(enabled: Boolean) = mutate { it.copy(disguiseIconEnabled = enabled) }

    /** Update the recovery security question/answer from the Settings security-question modal. */
    fun setRecovery(setup: RecoverySetup) {
        viewModelScope.launch {
            credentialStore.setRecovery(setup)
            _state.update { it.copy(hasRecovery = true) }
        }
    }

    /** Re-read derived credential context (e.g. after returning from the Fake Password manager). */
    fun refreshDerived() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    decoyCount = credentialStore.decoySlots().size,
                    hasRecovery = credentialStore.recoveryInfo() != null,
                )
            }
        }
    }

    private fun mutate(transform: (VaultSettings) -> VaultSettings) {
        val next = transform(_state.value.settings)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch { settingsStore.save(next) }
    }
}
