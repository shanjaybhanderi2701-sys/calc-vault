package com.appblish.calculatorvault.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.auth.SecurityQuestion
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The recovery setup flow (PIN Recovery W2, W0 screens 02–06): security question → recovery
 * code display → confirm → one-time hint → complete. Reached from the setup intro sheet
 * (01), the grid warning banner (07), and later from Settings (W4). On completion it writes
 * Wrap B + Wrap C for the session DEK (via [RecoverySetupViewModel]) and calls [onDone].
 *
 * Disguise-safe throughout: it only ever renders inside the unlocked vault, and the hint
 * screen (05) explicitly states the calculator will never show the doorway code.
 */
@Composable
fun RecoverySetupScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoverySetupViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        when (state.step) {
            RecoverySetupStep.QUESTION ->
                QuestionStep(
                    state = state,
                    onSelectPreset = viewModel::selectPresetQuestion,
                    onUseCustom = viewModel::useCustomQuestion,
                    onCustomChange = viewModel::setCustomQuestion,
                    onAnswerChange = viewModel::setAnswer,
                    onContinue = viewModel::leaveQuestion,
                    onCancel = onCancel,
                )
            RecoverySetupStep.CODE ->
                CodeStep(
                    code = state.recoveryCode,
                    saved = state.codeSaved,
                    onSavedChange = viewModel::setCodeSaved,
                    onContinue = viewModel::leaveCode,
                )
            RecoverySetupStep.CONFIRM ->
                ConfirmStep(
                    input = state.confirmInput,
                    error = state.confirmError,
                    saveError = state.saveError,
                    saving = state.saving,
                    onInputChange = viewModel::setConfirmInput,
                    onConfirm = viewModel::confirmAndSave,
                    onBackToCode = viewModel::backToCode,
                )
            RecoverySetupStep.HINT -> HintStep(onFinish = viewModel::finishHint)
            RecoverySetupStep.DONE -> DoneStep(onBackToVault = onDone)
        }
    }
}

// --- 02 · Security question --------------------------------------------------------------

@Composable
private fun QuestionStep(
    state: RecoverySetupUiState,
    onSelectPreset: (SecurityQuestion) -> Unit,
    onUseCustom: () -> Unit,
    onCustomChange: (String) -> Unit,
    onAnswerChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    StepScaffold(
        stepLabel = "Recovery · 1 of 3",
        title = "Security question",
        body =
            "Pick something only you know and won't forget. This is one of two ways back " +
                "into your vault if you forget your PIN.",
        primaryText = "Continue",
        primaryEnabled = state.canLeaveQuestion,
        onPrimary = onContinue,
        secondaryText = "Maybe later",
        onSecondary = onCancel,
    ) {
        if (state.useCustomQuestion) {
            OutlinedTextField(
                value = state.customQuestion,
                onValueChange = onCustomChange,
                singleLine = true,
                label = { Text("Your question") },
                modifier = Modifier.fillMaxWidth().testTag("recovery-custom-question"),
            )
        } else {
            QuestionPicker(selected = state.presetQuestion, onSelect = onSelectPreset)
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = "+ Write my own question",
                style = VaultTheme.typography.labelLarge,
                color = colors.accent,
                modifier = Modifier.clickable(onClick = onUseCustom).testTag("recovery-write-own"),
            )
        }
        Spacer(Modifier.height(spacing.lg))
        OutlinedTextField(
            value = state.answer,
            onValueChange = onAnswerChange,
            singleLine = true,
            placeholder = { Text("Type your answer") },
            modifier = Modifier.fillMaxWidth().testTag("recovery-answer"),
        )
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = "Not case-sensitive. Spaces are ignored. Stored only as an encrypted key — never as text.",
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun QuestionPicker(
    selected: SecurityQuestion,
    onSelect: (SecurityQuestion) -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                    .clickable { expanded = true }
                    .testTag("recovery-question-picker")
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Text(
                text = selected.prompt,
                style = VaultTheme.typography.bodyLarge,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Choose question",
                tint = colors.textSecondary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SecurityQuestion.entries.forEach { question ->
                DropdownMenuItem(
                    text = { Text(question.prompt, color = colors.textPrimary) },
                    onClick = {
                        onSelect(question)
                        expanded = false
                    },
                )
            }
        }
    }
}

// --- 03 · Recovery code display ----------------------------------------------------------

@Composable
private fun CodeStep(
    code: String,
    saved: Boolean,
    onSavedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    StepScaffold(
        stepLabel = "Recovery · 2 of 3",
        title = "Your recovery code",
        body =
            "Save this somewhere safe — a password manager, or written down. If you forget " +
                "your PIN and your security answer, this is the only way in. We can't show it again.",
        primaryText = "Continue",
        primaryEnabled = saved,
        onPrimary = onContinue,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                    .padding(vertical = spacing.xl),
        ) {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                color = colors.accent,
                fontSize = 24.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("recovery-code"),
            )
        }
        Spacer(Modifier.height(spacing.md))
        PillButton(
            text = if (copied) "Copied" else "Copy code",
            onClick = {
                clipboard.setText(AnnotatedString(code))
                copied = true
            },
            style = PillButtonStyle.Secondary,
        )
        Spacer(Modifier.height(spacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = saved,
                onCheckedChange = onSavedChange,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent),
                modifier = Modifier.testTag("recovery-code-saved"),
            )
            Text(
                text = "I've saved my recovery code somewhere safe.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
        }
    }
}

// --- 04 · Recovery code confirm ----------------------------------------------------------

@Composable
private fun ConfirmStep(
    input: String,
    error: Boolean,
    saveError: String?,
    saving: Boolean,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onBackToCode: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    StepScaffold(
        stepLabel = "Recovery · 3 of 3",
        title = "Confirm your code",
        body = "Enter the recovery code you just saved, so we know you've got it right.",
        primaryText = if (saving) "Saving…" else "Confirm & continue",
        primaryEnabled = input.isNotBlank() && !saving,
        onPrimary = onConfirm,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { onInputChange(formatRecoveryCodeInput(it)) },
            singleLine = true,
            isError = error || saveError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
            modifier = Modifier.fillMaxWidth().testTag("recovery-confirm-input"),
        )
        Spacer(Modifier.height(spacing.sm))
        if (error) {
            Text(
                text = "That code doesn't match. Check it and try again.",
                style = VaultTheme.typography.labelMedium,
                color = colors.destructive,
                modifier = Modifier.testTag("recovery-confirm-error"),
            )
        } else if (saveError != null) {
            Text(text = saveError, style = VaultTheme.typography.labelMedium, color = colors.destructive)
        } else {
            Text(
                text = "Dashes are added for you. Not case-sensitive.",
                style = VaultTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.height(spacing.lg))
        Text(
            text = "Lost it? Go back to see it once more.",
            style = VaultTheme.typography.labelLarge,
            color = colors.accent,
            modifier = Modifier.clickable(onClick = onBackToCode).testTag("recovery-back-to-code"),
        )
    }
}

// --- 05 · One-time hint (owner-ratified KEEP) --------------------------------------------

@Composable
private fun HintStep(onFinish: () -> Unit) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    StepScaffold(
        stepLabel = null,
        title = "How to start recovery later",
        body =
            "This is the only place we can show you this — for your safety it never appears " +
                "on the calculator, where someone could see it.",
        primaryText = "Got it — finish",
        primaryEnabled = true,
        onPrimary = onFinish,
    ) {
        HintRow(index = "1") {
            Text(
                text = "On the calculator, type 11223344 then tap =",
                style = VaultTheme.typography.bodyLarge,
                color = colors.textPrimary,
            )
        }
        Spacer(Modifier.height(spacing.md))
        HintRow(index = "2") {
            Text(
                text = "Or just enter your PIN wrong 3 times — a quiet \"try another way\" appears.",
                style = VaultTheme.typography.bodyLarge,
                color = colors.textPrimary,
            )
        }
        Spacer(Modifier.height(spacing.lg))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(spacing.lg),
        ) {
            Text(
                text =
                    "Write this down next to your recovery code. You can always find it again " +
                        "in Settings → PIN Recovery.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun HintRow(
    index: String,
    content: @Composable () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(28.dp).background(colors.accent, CircleShape),
        ) {
            Text(text = index, style = VaultTheme.typography.labelLarge, color = colors.onAccent)
        }
        Spacer(Modifier.size(spacing.md))
        content()
    }
}

// --- 06 · Complete ----------------------------------------------------------------------

@Composable
private fun DoneStep(onBackToVault: () -> Unit) {
    val colors = VaultTheme.colors
    StepScaffold(
        stepLabel = null,
        title = "Recovery is on",
        body =
            "You now have two backup ways into your vault: your security answer and your " +
                "recovery code. The warning banner is gone.",
        primaryText = "Back to vault",
        primaryEnabled = true,
        onPrimary = onBackToVault,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(84.dp).testTag("recovery-done-check"),
            )
        }
    }
}

// --- Shared scaffold --------------------------------------------------------------------

/**
 * The shared vertical layout for every setup step: optional step label, H1, body, a
 * step-specific [content] block, then the bottom CTA(s). Scrolls so the keyboard never
 * clips the primary action.
 */
@Composable
private fun StepScaffold(
    stepLabel: String?,
    title: String,
    body: String,
    primaryText: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl, vertical = spacing.xxl),
    ) {
        if (stepLabel != null) {
            Text(text = stepLabel, style = VaultTheme.typography.labelMedium, color = colors.accent)
            Spacer(Modifier.height(spacing.sm))
        }
        Text(text = title, style = VaultTheme.typography.headlineSmall, color = colors.textPrimary)
        Spacer(Modifier.height(spacing.md))
        Text(text = body, style = VaultTheme.typography.bodyMedium, color = colors.textSecondary)
        Spacer(Modifier.height(spacing.xl))
        content()
        Spacer(Modifier.height(spacing.xxl))
        PillButton(
            text = primaryText,
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.testTag("recovery-primary"),
        )
        if (secondaryText != null && onSecondary != null) {
            Spacer(Modifier.height(spacing.sm))
            PillButton(text = secondaryText, onClick = onSecondary, style = PillButtonStyle.Secondary)
        }
    }
}

/**
 * Format live recovery-code entry (04) to the displayed `XXXX-XXXX-XXXX-XXXX` shape: keep
 * only alphanumerics, uppercase, cap at 16 characters, and re-insert dashes every 4. The
 * value still round-trips through [com.appblish.calculatorvault.vault.crypto.RecoverySecrets]
 * normalization for the actual match, so this is purely cosmetic.
 */
internal fun formatRecoveryCodeInput(raw: String): String {
    val cleaned = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(16)
    return cleaned.chunked(4).joinToString("-")
}
