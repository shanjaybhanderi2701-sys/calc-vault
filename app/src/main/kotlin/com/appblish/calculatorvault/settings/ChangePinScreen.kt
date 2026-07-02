package com.appblish.calculatorvault.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.calculator.PinCalculatorScreen
import com.appblish.calculatorvault.calculator.changePinHint
import com.appblish.calculatorvault.calculator.confirmPinHint

/**
 * Settings → Change unlock PIN. Verify current → enter new → confirm, all on the disguised
 * calculator PIN surface. On success it calls [onDone]; [onBack] backs out of any step.
 */
@Composable
fun ChangePinScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChangePinViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.stage) {
        if (state.stage == ChangePinStage.DONE) onDone()
    }

    val title =
        when (state.stage) {
            ChangePinStage.VERIFY -> "Current password"
            ChangePinStage.NEW -> "New password"
            ChangePinStage.CONFIRM -> "Confirm password"
            ChangePinStage.DONE -> "Done"
        }

    val hint: AnnotatedString =
        when (state.stage) {
            ChangePinStage.VERIFY -> AnnotatedString(state.error ?: "Enter your current 4-digit password")
            ChangePinStage.NEW -> if (state.error != null) AnnotatedString(state.error!!) else changePinHint()
            ChangePinStage.CONFIRM -> confirmPinHint()
            ChangePinStage.DONE -> AnnotatedString("")
        }

    PinCalculatorScreen(
        title = title,
        hint = hint,
        onSubmit = { pin ->
            when (state.stage) {
                ChangePinStage.VERIFY -> viewModel.onVerify(pin)
                ChangePinStage.NEW -> viewModel.onNew(pin)
                ChangePinStage.CONFIRM -> viewModel.onConfirm(pin)
                ChangePinStage.DONE -> Unit
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}
