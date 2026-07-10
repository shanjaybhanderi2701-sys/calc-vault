package com.appblish.calculatorvault.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.auth.SecurityQuestion
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * Settings → PIN Recovery (PIN Recovery W4, W0 design screen 14) plus its two self-contained
 * management sub-flows (regenerate recovery code 03/04, change security question). The
 * `11223344 =` reminder is surfaced **only here** (and the one-time setup hint), behind the
 * lock — never on the calculator (W0 §16).
 */
@Composable
fun PinRecoveryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PinRecoveryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val title =
        when (state.stage) {
            RecoveryStage.HUB -> "PIN Recovery"
            RecoveryStage.REGEN_SHOW -> "Your recovery code"
            RecoveryStage.REGEN_CONFIRM -> "Confirm your code"
            RecoveryStage.REGEN_DONE -> "Recovery code updated"
            RecoveryStage.CHANGE_QUESTION -> "Change security question"
            RecoveryStage.CHANGE_DONE -> "Security question updated"
        }

    val onScreenBack: () -> Unit = { if (state.stage == RecoveryStage.HUB) onBack() else viewModel.backToHub() }

    SettingsScaffold(title = title, onBack = onScreenBack, modifier = modifier) {
        when (state.stage) {
            RecoveryStage.HUB -> RecoveryHub(state, viewModel)
            RecoveryStage.REGEN_SHOW -> RegenShow(state, viewModel)
            RecoveryStage.REGEN_CONFIRM -> RegenConfirm(state, viewModel)
            RecoveryStage.REGEN_DONE -> DonePane(
                message = "Save the new code somewhere safe — your old code no longer works.",
                onDone = viewModel::backToHub
            )
            RecoveryStage.CHANGE_QUESTION -> ChangeQuestion(state, viewModel)
            RecoveryStage.CHANGE_DONE -> DonePane(
                message = "Your new security question and answer are ready to use.",
                onDone = viewModel::backToHub
            )
        }
    }
}

@Composable
private fun RecoveryHub(
    state: PinRecoveryUiState,
    viewModel: PinRecoveryViewModel,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    // Status chip.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(horizontal = spacing.lg, vertical = spacing.md)
                .background(
                    color = if (state.hasRecovery) colors.accent.copy(alpha = 0.12f) else colors.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ).padding(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        Icon(
            imageVector = if (state.hasRecovery) Icons.Filled.CheckCircle else Icons.Filled.Lock,
            contentDescription = null,
            tint = if (state.hasRecovery) colors.accent else colors.textSecondary,
        )
        Text(
            text = if (state.hasRecovery) "Recovery is set up" else "Not set up yet",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = spacing.sm),
        )
    }

    // The 11223344 = reminder card — the ONLY place this hint is surfaced (soft-green, educational).
    Column(
        modifier =
            Modifier
                .padding(horizontal = spacing.lg, vertical = spacing.xs)
                .fillMaxWidth()
                .background(color = colors.accent.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp))
                .padding(spacing.md),
    ) {
        Text(
            text = "💡 Forgot your PIN?",
            style = VaultTheme.typography.labelLarge,
            color = colors.accent,
        )
        Text(
            text = "On the calculator, type 11223344 then =, or enter your PIN wrong 3 times.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.xs),
        )
    }

    SettingsSectionHeader("Recovery methods")

    if (state.hasRecovery) {
        ListRow(
            title = "Security question",
            subtitle = state.questionPrompt ?: state.question?.prompt ?: "Tap to set a question",
            leadingIcon = Icons.Filled.Lock,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = viewModel::startChangeQuestion,
        )
        ListRow(
            title = "Regenerate recovery code",
            subtitle = "Replaces your old code — you'll save the new one",
            leadingIcon = Icons.Filled.Refresh,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = viewModel::startRegenerate,
        )
        ListRow(
            title = "Change security question",
            subtitle = "Pick a new question & answer",
            leadingIcon = Icons.Filled.Lock,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = viewModel::startChangeQuestion,
        )
    } else {
        Text(
            text =
                "Recovery isn't set up. After you hide your first item you'll be offered a security " +
                    "question and a recovery code — the only two ways back in if you forget your PIN.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
        )
    }
}

@Composable
private fun RegenShow(
    state: PinRecoveryUiState,
    viewModel: PinRecoveryViewModel,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val context = LocalContext.current

    Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text =
                "Save this somewhere safe — a password manager, or written down. If you forget your " +
                    "PIN and your security answer, this is the only way in. We can't show it again.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Text(
            text = state.newCode,
            style = VaultTheme.typography.headlineSmall,
            color = colors.accent,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                    .padding(vertical = spacing.lg),
        )
        PillButton(
            text = "Copy code",
            onClick = { copyToClipboard(context, state.newCode) },
            style = PillButtonStyle.Secondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.codeSaved,
                onCheckedChange = viewModel::setCodeSaved,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent),
            )
            Text(
                text = "I've saved my recovery code somewhere safe.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
        }
        PillButton(
            text = "Continue",
            onClick = viewModel::regenerateContinue,
            enabled = state.codeSaved,
        )
    }
}

@Composable
private fun RegenConfirm(
    state: PinRecoveryUiState,
    viewModel: PinRecoveryViewModel,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text = "Enter the recovery code you just saved, so we know you've got it right.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        OutlinedTextField(
            value = state.confirmEntry,
            onValueChange = viewModel::setConfirmEntry,
            label = { Text("Recovery code") },
            singleLine = true,
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) {
            Text(text = state.error, style = VaultTheme.typography.bodyMedium, color = colors.destructive)
        }
        PillButton(
            text = "Confirm & save",
            onClick = viewModel::confirmRegenerate,
            enabled = !state.busy && state.confirmEntry.isNotBlank(),
        )
    }
}

@Composable
private fun ChangeQuestion(
    state: PinRecoveryUiState,
    viewModel: PinRecoveryViewModel,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = "Pick something only you know and won't forget.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        SecurityQuestion.entries.forEach { question ->
            ListRow(
                title = question.prompt,
                leadingIcon = if (question == state.draftQuestion) Icons.Filled.CheckCircle else null,
                leadingChipColor = if (question == state.draftQuestion) colors.accent else colors.surfaceVariant,
                onClick = { viewModel.setDraftQuestion(question) },
            )
        }
        OutlinedTextField(
            value = state.draftAnswer,
            onValueChange = viewModel::setDraftAnswer,
            label = { Text("Your answer") },
            singleLine = true,
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Not case-sensitive. Spaces are ignored. Stored only as an encrypted key — never as text.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textDisabled,
        )
        if (state.error != null) {
            Text(text = state.error, style = VaultTheme.typography.bodyMedium, color = colors.destructive)
        }
        PillButton(
            text = "Save",
            onClick = viewModel::confirmChangeQuestion,
            enabled = !state.busy && state.draftAnswer.isNotBlank(),
        )
    }
}

@Composable
private fun DonePane(
    message: String,
    onDone: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.padding(vertical = spacing.md),
        )
        Text(text = message, style = VaultTheme.typography.bodyMedium, color = colors.textSecondary)
        PillButton(text = "Done", onClick = onDone)
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Recovery code", text))
}
