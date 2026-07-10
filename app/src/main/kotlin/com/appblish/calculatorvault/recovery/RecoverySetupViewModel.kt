package com.appblish.calculatorvault.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.SecurityQuestion
import com.appblish.calculatorvault.vault.crypto.RecoverySecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** The linear steps of the recovery setup flow (W0 screens 02 → 03 → 04 → 05 → 06). */
enum class RecoverySetupStep {
    /** 02 — pick a preset or custom security question and type the answer (→ Wrap B). */
    QUESTION,

    /** 03 — show the generated recovery code once, gated by the "I've saved it" checkbox. */
    CODE,

    /** 04 — re-enter the code to prove it was recorded (→ writes Wrap B + C on success). */
    CONFIRM,

    /** 05 — one-time hint teaching the two doorways (owner-ratified KEEP). */
    HINT,

    /** 06 — setup complete; the grid banner is now permanently gone. */
    DONE,
}

data class RecoverySetupUiState(
    val step: RecoverySetupStep = RecoverySetupStep.QUESTION,
    val presetQuestion: SecurityQuestion = SecurityQuestion.DEFAULT,
    val useCustomQuestion: Boolean = false,
    val customQuestion: String = "",
    val answer: String = "",
    val recoveryCode: String = "",
    val codeSaved: Boolean = false,
    val confirmInput: String = "",
    val confirmError: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
) {
    /** The prompt that will be persisted + shown on the recovery-entry screen (W3). */
    val effectiveQuestion: String
        get() = if (useCustomQuestion) customQuestion.trim() else presetQuestion.prompt

    /** 02 → 03 is allowed once the question is non-blank and the answer survives normalization. */
    val canLeaveQuestion: Boolean
        get() = effectiveQuestion.isNotBlank() && RecoverySecrets.normalizeAnswer(answer).isNotEmpty()

    /** 04 confirm matches (dash/space/case-insensitive, via the same normalization as the wrap). */
    val confirmMatches: Boolean
        get() =
            RecoverySecrets.normalizeRecoveryCode(confirmInput) ==
                RecoverySecrets.normalizeRecoveryCode(recoveryCode)
}

/**
 * Drives the recovery setup flow (PIN Recovery W2, W0 screens 02–06). Generates one
 * high-entropy recovery code up front ([RecoverySecrets.generateRecoveryCode]), collects the
 * security question + answer, and on a matching code re-entry writes **Wrap B** (answer) and
 * **Wrap C** (code) against the session DEK via [RecoveryManager.setUp] — a pure envelope
 * add, no blob is re-encrypted (spec §1). The plaintext answer/code live only in this
 * in-memory state for the duration of setup; only their derived wraps ever touch disk.
 */
class RecoverySetupViewModel(
    private val recoveryManager: RecoveryManager = RecoveryGraph.recoveryManager,
    random: SecureRandom = SecureRandom(),
) : ViewModel() {
    constructor() : this(RecoveryGraph.recoveryManager)

    private val _uiState =
        MutableStateFlow(RecoverySetupUiState(recoveryCode = RecoverySecrets.generateRecoveryCode(random)))
    val uiState: StateFlow<RecoverySetupUiState> = _uiState.asStateFlow()

    fun selectPresetQuestion(question: SecurityQuestion) {
        _uiState.update { it.copy(presetQuestion = question, useCustomQuestion = false) }
    }

    fun useCustomQuestion() {
        _uiState.update { it.copy(useCustomQuestion = true) }
    }

    fun setCustomQuestion(text: String) {
        _uiState.update { it.copy(customQuestion = text) }
    }

    fun setAnswer(text: String) {
        _uiState.update { it.copy(answer = text) }
    }

    fun leaveQuestion() {
        if (_uiState.value.canLeaveQuestion) _uiState.update { it.copy(step = RecoverySetupStep.CODE) }
    }

    fun setCodeSaved(saved: Boolean) {
        _uiState.update { it.copy(codeSaved = saved) }
    }

    fun leaveCode() {
        if (_uiState.value.codeSaved) _uiState.update { it.copy(step = RecoverySetupStep.CONFIRM) }
    }

    /** Back link on 04 → 03 (last chance to view the code). */
    fun backToCode() {
        _uiState.update { it.copy(step = RecoverySetupStep.CODE, confirmInput = "", confirmError = false) }
    }

    fun setConfirmInput(text: String) {
        _uiState.update { it.copy(confirmInput = text, confirmError = false) }
    }

    /**
     * 04 → write the envelope. On a code match, derive + persist Wrap B/C and advance to the
     * one-time hint (05); on a mismatch, show the inline error and stay. A write failure
     * (e.g. the session PIN no longer unwraps the vault) surfaces as [saveError].
     */
    fun confirmAndSave() {
        val state = _uiState.value
        if (state.saving) return
        if (!state.confirmMatches) {
            _uiState.update { it.copy(confirmError = true) }
            return
        }
        _uiState.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val result =
                runCatching {
                    recoveryManager.setUp(
                        question = state.effectiveQuestion,
                        securityAnswer = state.answer,
                        recoveryCode = state.recoveryCode,
                    )
                }
            _uiState.update {
                if (result.isSuccess) {
                    // Recovery is now configured, so the intro sheet must never resurface.
                    RecoveryPromptState.introOfferedThisSession = true
                    it.copy(step = RecoverySetupStep.HINT, saving = false)
                } else {
                    it.copy(saving = false, saveError = "Couldn't save recovery. Please try again.")
                }
            }
        }
    }

    fun finishHint() {
        _uiState.update { it.copy(step = RecoverySetupStep.DONE) }
    }
}
