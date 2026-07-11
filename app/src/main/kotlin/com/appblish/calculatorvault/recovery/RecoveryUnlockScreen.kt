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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.calculator.PinCalculatorScreen
import com.appblish.calculatorvault.calculator.confirmPinHint
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import kotlin.math.ceil

/**
 * The recovery unlock + set-new-PIN flow (PIN Recovery W0 09/10 → 11, APP-325 W3): the real
 * screen that replaces the W2 seam placeholder at the same `recovery/unlock/{method}` route.
 *
 * The user proves it's them with the security answer (Wrap B) or the recovery code (Wrap C),
 * then sets a new PIN. The data key is unwrapped via the recovery secret and **only Wrap A**
 * is re-created under the new PIN — the hidden files never move and the other recovery paths
 * keep working (spec §1.3 / §5.2). Wrong attempts back off ([RecoveryUnlockViewModel]); if
 * recovery was never configured the screen shows the honest dead-end, never a false "contact
 * support" (spec §1.5). On success [onDone] enters the freshly-unlocked vault.
 */
@Composable
fun RecoveryUnlockScreen(
    method: RecoveryMethod,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryUnlockViewModel = viewModel(key = method.name) { RecoveryUnlockViewModel(method) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors

    LaunchedEffect(state.stage) {
        if (state.stage == RecoveryUnlockStage.DONE) onDone()
    }

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        when (state.stage) {
            RecoveryUnlockStage.ENTER_SECRET ->
                EnterSecretStep(
                    method = state.method,
                    question = state.question,
                    error = state.error,
                    lockoutRemainingMillis = state.lockoutRemainingMillis,
                    busy = state.busy,
                    onSubmit = viewModel::onSubmitSecret,
                    onBack = onBack,
                )

            RecoveryUnlockStage.NEW_PIN ->
                PinCalculatorScreen(
                    title = "New PIN",
                    hint =
                        if (state.error != null) {
                            AnnotatedString(state.error!!)
                        } else {
                            AnnotatedString("Choose a new 4-digit PIN, then tap \"=\"")
                        },
                    onSubmit = viewModel::onNewPin,
                    onBack = onBack,
                )

            RecoveryUnlockStage.CONFIRM_PIN ->
                PinCalculatorScreen(
                    title = "Confirm PIN",
                    hint = confirmPinHint(),
                    onSubmit = viewModel::onConfirmPin,
                    onBack = onBack,
                )

            RecoveryUnlockStage.DONE -> DoneStep()

            RecoveryUnlockStage.UNRECOVERABLE -> UnrecoverableStep(onBack = onBack)
        }
    }
}

@Composable
private fun EnterSecretStep(
    method: RecoveryMethod,
    question: String?,
    error: String?,
    lockoutRemainingMillis: Long,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var input by rememberSaveable { mutableStateOf("") }
    val isCode = method == RecoveryMethod.RECOVERY_CODE
    val lockedOut = lockoutRemainingMillis > 0L

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
        Spacer(Modifier.height(spacing.md))
        Text(
            text =
                if (isCode) {
                    "Enter the 16-character code you saved at setup. You'll set a new PIN next — " +
                        "your hidden files stay exactly where they are."
                } else {
                    (question ?: "Your security question") +
                        "\n\nAnswer it to set a new PIN. Your hidden files stay exactly where they are."
                },
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(spacing.xl))
        OutlinedTextField(
            value = input,
            onValueChange = { input = if (isCode) formatRecoveryCodeInput(it) else it },
            singleLine = true,
            enabled = !busy && !lockedOut,
            isError = error != null,
            keyboardOptions =
                KeyboardOptions(keyboardType = if (isCode) KeyboardType.Ascii else KeyboardType.Text),
            placeholder = { Text(if (isCode) "XXXX-XXXX-XXXX-XXXX" else "Your answer") },
            modifier = Modifier.fillMaxWidth().testTag("recovery-unlock-secret-input"),
        )
        Spacer(Modifier.height(spacing.sm))
        if (lockedOut) {
            Text(
                text = "Too many tries. Try again in ${formatLockout(lockoutRemainingMillis)}.",
                style = VaultTheme.typography.labelMedium,
                color = colors.destructive,
                modifier = Modifier.testTag("recovery-unlock-lockout"),
            )
        } else if (error != null) {
            Text(
                text = error,
                style = VaultTheme.typography.labelMedium,
                color = colors.destructive,
                modifier = Modifier.testTag("recovery-unlock-error"),
            )
        } else if (!isCode) {
            Text(
                text = "Not case-sensitive; extra spaces don't matter.",
                style = VaultTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.height(spacing.xxl))
        PillButton(
            text = if (busy) "Checking…" else "Continue",
            onClick = { onSubmit(input) },
            enabled = input.isNotBlank() && !busy && !lockedOut,
            modifier = Modifier.fillMaxWidth().testTag("recovery-unlock-submit"),
        )
    }
}

@Composable
private fun DoneStep() {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(spacing.xl),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.testTag("recovery-unlock-done"),
        )
        Text(text = "PIN reset", style = VaultTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(
            text = "Your new PIN is set and your vault is open. Your files are exactly where you left them.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun UnrecoverableStep(onBack: () -> Unit) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
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
            modifier = Modifier.testTag("recovery-unlock-unrecoverable"),
        )
        Text(text = "Recovery isn't set up", style = VaultTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(
            text =
                "Without a security answer or recovery code, a forgotten PIN can't be reset by " +
                    "anyone — including us. There's no backdoor by design. Any files still in the " +
                    "vault stay encrypted and locked.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

/** Human-readable lockout countdown, e.g. `45s`, `2m`. */
private fun formatLockout(millis: Long): String {
    val totalSeconds = ceil(millis / 1000.0).toLong().coerceAtLeast(1)
    return if (totalSeconds < 60) {
        "${totalSeconds}s"
    } else {
        val minutes = ceil(totalSeconds / 60.0).toLong()
        "${minutes}m"
    }
}
