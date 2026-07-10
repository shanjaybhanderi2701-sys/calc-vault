package com.appblish.calculatorvault.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.auth.RecoverySetup
import com.appblish.calculatorvault.auth.SecurityQuestion
import com.appblish.calculatorvault.recovery.RecoveryGraph
import com.appblish.calculatorvault.recovery.RecoveryManager
import com.appblish.calculatorvault.vault.crypto.RecoveryReWrapper
import com.appblish.calculatorvault.vault.crypto.RecoverySecrets
import com.appblish.calculatorvault.vault.crypto.RecoveryUpdateOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Which sub-surface of Settings → PIN Recovery (screen 14) is showing. */
enum class RecoveryStage {
    /** The management hub: status, hint card, and the three action rows. */
    HUB,

    /** Regenerate step 03: the fresh code is shown with Copy + "I've saved it" checkbox. */
    REGEN_SHOW,

    /** Regenerate step 04: re-enter the code to prove it was saved (re-wrap happens on match). */
    REGEN_CONFIRM,

    /** Regenerate complete: the new code now wraps the DEK (old code dead). */
    REGEN_DONE,

    /** Change security question: pick a question + type the new answer. */
    CHANGE_QUESTION,

    /** Change-question complete: Wrap B now wraps the DEK under the new answer. */
    CHANGE_DONE,
}

/**
 * State for Settings → PIN Recovery. Holds the recovery status (for the chip + "view
 * question" row) and the transient draft of whichever sub-flow is active.
 */
data class PinRecoveryUiState(
    val loaded: Boolean = false,
    val hasRecovery: Boolean = false,
    val question: SecurityQuestion? = null,
    // The exact survive-uninstall prompt string (APP-338). Preferred over [question] for
    // display so a *custom* prompt (not one of the [SecurityQuestion] presets) still shows
    // after a reinstall, when the app-private legacy record is gone.
    val questionPrompt: String? = null,
    val stage: RecoveryStage = RecoveryStage.HUB,
    // Regenerate-code draft (never persisted; the code lives only here until confirmed).
    val newCode: String = "",
    val codeSaved: Boolean = false,
    val confirmEntry: String = "",
    // Change-question draft.
    val draftQuestion: SecurityQuestion = SecurityQuestion.DEFAULT,
    val draftAnswer: String = "",
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * Backs Settings → PIN Recovery (PIN Recovery W4, screen 14). Two management flows re-wrap a
 * single recovery slot over the **same** DEK via the W1 envelope ([RecoveryReWrapper]) — the
 * regenerate-code path replaces Wrap C, the change-question path replaces Wrap B — so no blob
 * is ever re-encrypted and the PIN unlock keeps working throughout.
 *
 * **Regenerate ordering (deliberate).** A fresh code is generated in memory and shown, but the
 * envelope is re-wrapped **only after** the user re-enters it correctly (step 04). Until then
 * the *old* code still unwraps the vault, so abandoning the flow after seeing but not saving
 * the new code never strands recovery.
 */
class PinRecoveryViewModel(
    private val store: CredentialStore,
    private val reWrapper: RecoveryReWrapper,
    private val recoveryManager: RecoveryManager,
    private val random: SecureRandom,
) : ViewModel() {
    constructor() : this(
        AuthGraph.credentialStore,
        AuthGraph.recoveryReWrapper,
        RecoveryGraph.recoveryManager,
        SecureRandom(),
    )

    private val _state = MutableStateFlow(PinRecoveryUiState())
    val state: StateFlow<PinRecoveryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Status + prompt derive from the survive-uninstall key file / metadata (APP-338),
            // so a reinstalled app that still holds the wraps correctly shows "Recovery is set
            // up" and the chosen question. The legacy app-private record is only a fallback to
            // resolve a preset enum for the change-question draft when the metadata is absent.
            val configured = recoveryManager.isConfigured()
            val prompt = recoveryManager.configuredQuestion()
            val legacyQuestion = store.recoveryInfo()?.question
            val question = presetFor(prompt) ?: legacyQuestion
            _state.update {
                it.copy(
                    loaded = true,
                    hasRecovery = configured,
                    question = question,
                    questionPrompt = prompt,
                    draftQuestion = question ?: SecurityQuestion.DEFAULT,
                )
            }
        }
    }

    /** Resolve a stored prompt string back to its preset [SecurityQuestion], or `null` if custom. */
    private fun presetFor(prompt: String?): SecurityQuestion? =
        prompt?.let { p -> SecurityQuestion.entries.firstOrNull { it.prompt == p } }

    // --- Regenerate recovery code (Wrap C) ------------------------------------------------

    /** Start the regenerate flow: mint a fresh code and show it (step 03). */
    fun startRegenerate() {
        val code = RecoverySecrets.generateRecoveryCode(random)
        _state.update {
            it.copy(
                stage = RecoveryStage.REGEN_SHOW,
                newCode = code,
                codeSaved = false,
                confirmEntry = "",
                error = null
            )
        }
    }

    /** The "I've saved my recovery code" checkbox — gates Continue on step 03. */
    fun setCodeSaved(saved: Boolean) = _state.update { it.copy(codeSaved = saved) }

    /** Advance from the code display (03) to the re-entry confirm (04). No-op until saved is checked. */
    fun regenerateContinue() {
        if (!_state.value.codeSaved) return
        _state.update { it.copy(stage = RecoveryStage.REGEN_CONFIRM, confirmEntry = "", error = null) }
    }

    fun setConfirmEntry(entry: String) = _state.update { it.copy(confirmEntry = entry, error = null) }

    /**
     * Confirm the re-entered code (step 04). On a normalized match, re-wrap Wrap C under the new
     * code; only then does the old code stop working. A mismatch or a re-wrap failure keeps the
     * old code in force.
     */
    fun confirmRegenerate() {
        val s = _state.value
        if (RecoverySecrets.normalizeRecoveryCode(s.confirmEntry) != RecoverySecrets.normalizeRecoveryCode(s.newCode)) {
            _state.update { it.copy(error = "That doesn't match the code above. Check and try again.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (reWrapper.replaceRecoveryCode(s.newCode)) {
                RecoveryUpdateOutcome.UPDATED ->
                    _state.update {
                        it.copy(stage = RecoveryStage.REGEN_DONE, busy = false, hasRecovery = true, error = null)
                    }
                else ->
                    _state.update { it.copy(busy = false, error = updateFailureMessage()) }
            }
        }
    }

    // --- Change security question (Wrap B) ------------------------------------------------

    /** Start the change-question flow, pre-filling the current question. */
    fun startChangeQuestion() {
        _state.update {
            it.copy(
                stage = RecoveryStage.CHANGE_QUESTION,
                draftQuestion = it.question ?: SecurityQuestion.DEFAULT,
                draftAnswer = "",
                error = null,
            )
        }
    }

    fun setDraftQuestion(question: SecurityQuestion) = _state.update { it.copy(draftQuestion = question, error = null) }

    fun setDraftAnswer(answer: String) = _state.update { it.copy(draftAnswer = answer, error = null) }

    /**
     * Re-wrap Wrap B under the new normalized answer, then persist the new question + hashed
     * answer to the credential store (display + legacy verify path) so both stay in step. A
     * blank answer, or a crypto re-wrap failure, is refused with the old question intact.
     */
    fun confirmChangeQuestion() {
        val s = _state.value
        if (RecoverySecrets.normalizeAnswer(s.draftAnswer).isEmpty()) {
            _state.update { it.copy(error = "Type an answer you'll remember.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (reWrapper.replaceSecurityAnswer(s.draftAnswer)) {
                RecoveryUpdateOutcome.UPDATED -> {
                    val existing = store.recoveryInfo()
                    store.setRecovery(
                        RecoverySetup(
                            question = s.draftQuestion,
                            answer = s.draftAnswer,
                            recoveryEmail = existing?.recoveryEmail.orEmpty(),
                            hint = existing?.hint.orEmpty(),
                        ),
                    )
                    // Keep the survive-uninstall prompt in step with the re-wrapped Wrap B, so a
                    // later reinstall shows the *new* question, not the old one (APP-338).
                    runCatching { recoveryManager.updateQuestion(s.draftQuestion.prompt) }
                    _state.update {
                        it.copy(
                            stage = RecoveryStage.CHANGE_DONE,
                            busy = false,
                            hasRecovery = true,
                            question = s.draftQuestion,
                            questionPrompt = s.draftQuestion.prompt,
                            error = null,
                        )
                    }
                }
                else ->
                    _state.update { it.copy(busy = false, error = updateFailureMessage()) }
            }
        }
    }

    /** Return to the hub, clearing any transient secret draft. */
    fun backToHub() =
        _state.update {
            it.copy(stage = RecoveryStage.HUB, newCode = "", confirmEntry = "", codeSaved = false, error = null)
        }

    private fun updateFailureMessage(): String =
        "Couldn't update recovery — allow “All files access” and make sure your vault is unlocked, then try again."
}
