package com.appblish.calculatorvault.onboarding

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
 * [OnboardingViewModel] step and calling [onComplete] once onboarding is flagged done.
 *
 * xlock parity (APP-212): the wizard is a clean calculator/PIN setup — language → create PIN
 * → confirm PIN → two intro cards. There is deliberately **no** upfront All Files Access
 * wall and **no** security-question step: permissions are primed in context at first use and
 * the recovery question is deferred until after the first real vault operation.
 */
@Composable
fun OnboardingRoute(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onComplete()
    }

    when (state.step) {
        OnboardingStep.LANGUAGE ->
            LanguageSelectScreen(
                selected = state.language,
                onSelect = viewModel::selectLanguage,
                onDone = viewModel::onLanguageDone,
                modifier = modifier,
            )

        OnboardingStep.CREATE_PIN ->
            PinCalculatorScreen(
                title = null,
                hint = createPinHint(),
                onSubmit = viewModel::onPinCreated,
                onBack = viewModel::onBack,
                modifier = modifier,
            )

        OnboardingStep.CONFIRM_PIN ->
            PinCalculatorScreen(
                title = null,
                hint = confirmPinHint(),
                onSubmit = viewModel::onPinConfirmed,
                onBack = viewModel::onBack,
                modifier = modifier,
            )

        OnboardingStep.INTRO_PRIVATE ->
            IntroCardScreen(
                title = "Private Apps & Media Vault",
                body = "Securely hide apps, photos, videos, audio and important files.",
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
