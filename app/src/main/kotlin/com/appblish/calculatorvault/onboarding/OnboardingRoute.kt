package com.appblish.calculatorvault.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.calculator.CalculatorKeypad
import com.appblish.calculatorvault.calculator.PinCalculatorScreen
import com.appblish.calculatorvault.calculator.confirmPinHint
import com.appblish.calculatorvault.calculator.createPinHint

/**
 * Hosts the whole first-run wizard, rendering the screen for the current
 * [OnboardingViewModel] step and calling [onComplete] once onboarding is flagged done. The
 * file-access permission request is launched here on the Allow step; whatever the user
 * grants, the wizard advances.
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

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { viewModel.onFileAccessHandled() }

    when (state.step) {
        OnboardingStep.LANGUAGE ->
            LanguageSelectScreen(
                selected = state.language,
                onSelect = viewModel::selectLanguage,
                onDone = viewModel::onLanguageDone,
                modifier = modifier,
            )

        OnboardingStep.ALLOW_FILE_ACCESS ->
            AllowFileAccessScreen(
                onAllow = { permissionLauncher.launch(fileAccessPermissions()) },
                onNotNow = viewModel::onFileAccessHandled,
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

        OnboardingStep.SECURITY_QUESTION -> {
            // Deck shows the calculator (with the just-set PIN) behind the modal.
            CalculatorKeypad(display = "1111", onKey = {}, modifier = modifier)
            SecurityQuestionModal(
                onSave = viewModel::onSecuritySaved,
                onCancel = viewModel::onSecuritySkipped,
            )
        }

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

/** The storage/media permissions to request, matching the platform's model per API level. */
private fun fileAccessPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
