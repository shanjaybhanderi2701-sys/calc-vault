package com.appblish.calculatorvault.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.calculator.PinCalculatorScreen
import com.appblish.calculatorvault.calculator.confirmPinHint
import com.appblish.calculatorvault.calculator.createPinHint

/**
 * Hosts the whole first-run wizard, rendering the screen for the current
 * [OnboardingViewModel] step and calling [onComplete] once onboarding is flagged done —
 * passing the freshly created PIN so the host can open the vault directly (confirming the
 * PIN lands on **Home (Vault tab)**, not back on the calculator).
 *
 * Phase 1 (build spec §1): the wizard is a clean calculator/PIN setup — language → create PIN
 * → confirm PIN. There is deliberately **no** intro carousel (APP-528 removed the 2-page
 * viewpager), **no** upfront All Files Access wall, and **no** recovery step of any kind:
 * permissions are primed in context at first use, and PIN recovery does not exist in Phase 1
 * at all (build spec §0 — deferred to a later phase, accepted risk).
 */
@Composable
fun OnboardingRoute(
    onComplete: (pin: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onComplete(state.pinDraft)
    }

    when (state.step) {
        OnboardingStep.LANGUAGE ->
            Box(modifier = modifier) {
                LanguageSelectScreen(
                    selected = state.language,
                    onSelect = viewModel::selectLanguage,
                    onDone = viewModel::onLanguageDone,
                )
                // S3: "Setting Up Language" modal over the dimmed list while the ViewModel
                // holds the minimum loader dwell, then it advances to CREATE_PIN itself.
                if (state.applyingLanguage) {
                    LanguageApplyingOverlay()
                }
            }

        OnboardingStep.CREATE_PIN ->
            PinCalculatorScreen(
                title = null,
                hint = createPinHint(),
                onSubmit = viewModel::onPinCreated,
                onBack = viewModel::onBack,
                modifier = modifier,
                // P3-1: first-run only — pulse "=" once the 4th digit is in.
                highlightEqualsWhenComplete = true,
            )

        OnboardingStep.CONFIRM_PIN ->
            PinCalculatorScreen(
                title = null,
                hint = confirmPinHint(),
                onSubmit = viewModel::onPinConfirmed,
                onBack = viewModel::onBack,
                modifier = modifier,
                highlightEqualsWhenComplete = true,
            )
    }
}
