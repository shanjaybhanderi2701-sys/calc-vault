package com.appblish.calculatorvault.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
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
 * passing the freshly created PIN so the host can open the vault directly (spec §1.5: the
 * last intro card's Done lands on **Home (Vault tab)**, not back on the calculator).
 *
 * Phase 1 (build spec §1): the wizard is a clean calculator/PIN setup — language → create PIN
 * → confirm PIN → two intro cards. There is deliberately **no** upfront All Files Access
 * wall and **no** recovery step of any kind: permissions are primed in context at first use,
 * and PIN recovery does not exist in Phase 1 at all (build spec §0 — deferred to a later
 * phase, accepted risk).
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

        OnboardingStep.INTRO_PRIVATE ->
            IntroCardScreen(
                title = "Private Apps & Media Vault",
                // Design sign-off S6: body drops "apps" (the title keeps the docx wording).
                body = "Securely hide photos, videos, audio and important files",
                icon = Icons.Filled.Lock,
                ctaLabel = "Next",
                pageIndex = 0,
                pageCount = 2,
                onCta = viewModel::onIntroNext,
                onSkip = viewModel::onFinish,
                modifier = modifier,
            )

        OnboardingStep.INTRO_ICONS ->
            IntroCardScreen(
                title = "Custom App Icons",
                body = "Switch the app icon anytime to make your vault look like something else.",
                icon = Icons.Filled.Star,
                ctaLabel = "Done",
                pageIndex = 1,
                pageCount = 2,
                onCta = viewModel::onFinish,
                onSkip = viewModel::onFinish,
                modifier = modifier,
            )
    }
}
