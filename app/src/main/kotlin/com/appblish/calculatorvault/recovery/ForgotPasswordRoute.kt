package com.appblish.calculatorvault.recovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.calculator.PinCalculatorScreen
import com.appblish.calculatorvault.calculator.changePinHint

/**
 * Orchestrates the forgot-password flow: verify the security answer, set a new PIN on the
 * calculator, then hand back to the caller via [onReset]. [onBack] leaves the flow
 * (returning to the disguise calculator).
 */
@Composable
fun ForgotPasswordRoute(
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.stage) {
        if (state.stage == RecoveryStage.DONE) onReset()
    }

    when (state.stage) {
        RecoveryStage.VERIFY ->
            ForgotPasswordScreen(
                info = state.info,
                wrongAnswer = state.wrongAnswer,
                onVerify = viewModel::verifyAnswer,
                onBack = onBack,
                modifier = modifier,
            )

        RecoveryStage.RESET ->
            PinCalculatorScreen(
                title = "Reset Password",
                hint = changePinHint(),
                onSubmit = viewModel::resetPin,
                onBack = onBack,
                modifier = modifier,
            )

        RecoveryStage.DONE -> Unit
    }
}
