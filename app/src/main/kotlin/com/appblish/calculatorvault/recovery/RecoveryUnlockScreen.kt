package com.appblish.calculatorvault.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.calculator.PinCalculatorScreen
import com.appblish.calculatorvault.calculator.confirmPinHint
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod

/**
 * The forgot-PIN recovery flow (PIN Recovery W0 09/10 → 11): verify with the **security answer**
 * (Wrap B) or the **recovery code** (Wrap C), then set a new PIN which re-wraps **Wrap A only**.
 * Files are never touched — the reassurance copy says so, and it's literally true (spec §1.3).
 * This is the real screen W3 drops in at the [com.appblish.calculatorvault.navigation.VaultDestinations.RECOVERY_UNLOCK]
 * route where W2 stood up a placeholder.
 */
@Composable
fun RecoveryUnlockScreen(
    method: RecoveryMethod,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryUnlockViewModel =
        viewModel(key = "recovery-unlock-${method.arg}", factory = RecoveryUnlockViewModel.factory(method)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.stage) {
        if (state.stage == RecoveryUnlockStage.DONE) onDone()
    }

    when (state.stage) {
        RecoveryUnlockStage.VERIFY ->
            VerifyStep(
                method = method,
                question = state.question,
                secret = state.secretInput,
                error = state.error,
                verifying = state.verifying,
                onSecretChanged = viewModel::onSecretChanged,
                onSubmit = viewModel::onSubmitSecret,
                onBack = onBack,
                modifier = modifier,
            )

        RecoveryUnlockStage.NEW_PIN ->
            PinCalculatorScreen(
                title = "New password",
                hint = AnnotatedString(state.error ?: "Choose a new 4-digit password"),
                onSubmit = viewModel::onNewPin,
                onBack = onBack,
                modifier = modifier,
            )

        RecoveryUnlockStage.CONFIRM_PIN ->
            PinCalculatorScreen(
                title = "Confirm password",
                hint = confirmPinHint(),
                onSubmit = viewModel::onConfirmPin,
                onBack = onBack,
                modifier = modifier,
            )

        RecoveryUnlockStage.UNAVAILABLE -> UnavailableStep(onBack = onBack, modifier = modifier)

        // Nav pops on DONE (LaunchedEffect); render nothing to avoid a flash of stale content.
        RecoveryUnlockStage.DONE -> Unit
    }
}

@Composable
private fun VerifyStep(
    method: RecoveryMethod,
    question: String?,
    secret: String,
    error: String?,
    verifying: Boolean,
    onSecretChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val isCode = method == RecoveryMethod.RECOVERY_CODE
    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.sm)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = colors.textPrimary)
            }
        }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = spacing.xl)) {
            Text(
                text = if (isCode) "Enter recovery code" else "Answer security question",
                style = VaultTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.testTag("recovery-unlock-title"),
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                text =
                    if (isCode) {
                        "Type the 16-character code you saved at setup. Dashes, spaces and case don't matter."
                    } else {
                        question ?: "Your security question"
                    },
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(spacing.lg))
            OutlinedTextField(
                value = secret,
                onValueChange = onSecretChanged,
                singleLine = !isCode,
                placeholder = { Text(if (isCode) "XXXX-XXXX-XXXX-XXXX" else "Type your answer") },
                isError = error != null,
                modifier = Modifier.fillMaxWidth().testTag("recovery-unlock-secret"),
            )
            if (error != null) {
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = error,
                    style = VaultTheme.typography.labelMedium,
                    color = colors.destructive,
                    modifier = Modifier.testTag("recovery-unlock-error"),
                )
            }
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = "You'll set a new PIN next — your hidden files stay exactly where they are.",
                style = VaultTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(spacing.lg))
            PillButton(
                text = if (verifying) "Checking…" else "Continue",
                onClick = onSubmit,
                enabled = !verifying,
                modifier = Modifier.testTag("recovery-unlock-continue"),
            )
        }
    }
}

@Composable
private fun UnavailableStep(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.sm)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = colors.textPrimary)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = spacing.xl, vertical = spacing.xxl),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.destructive,
                modifier = Modifier.testTag("recovery-unlock-unavailable"),
            )
            Text(
                text = "Can't reset the PIN here",
                style = VaultTheme.typography.headlineSmall,
                color = colors.textPrimary,
            )
            Text(
                text =
                    "This device has no recovery set up, or “All files access” is off, so a forgotten " +
                        "PIN can't be reset — including by us. There's no backdoor by design. Your files " +
                        "are still here, just locked.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
    }
}
