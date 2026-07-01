package com.appblish.calculatorvault.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.appblish.calculatorvault.auth.RecoveryInfo
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The Calculator-Lock forgot-password verify step. Shows the configured security question,
 * the password hint, and where a reset can be sent (recovery email), then checks the typed
 * answer. If no recovery was ever configured ([info] is null), it says so instead of
 * offering a dead-end form.
 */
@Composable
fun ForgotPasswordScreen(
    info: RecoveryInfo?,
    wrongAnswer: Boolean,
    onVerify: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var answer by remember { mutableStateOf("") }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.canvas)
                .padding(bottom = spacing.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.sm)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Text(
                text = "Forgot Password",
                style = VaultTheme.typography.headlineSmall,
                color = colors.textPrimary,
            )
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xl, vertical = spacing.lg)) {
            if (info == null) {
                Text(
                    text = "No recovery was set up for this vault, so the PIN can't be reset here.",
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                return@Column
            }

            Text(
                text = info.question.prompt,
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text("Your answer") },
                singleLine = true,
                isError = wrongAnswer,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
            )
            if (wrongAnswer) {
                Text(
                    text = "That answer doesn't match. Try again.",
                    style = VaultTheme.typography.labelMedium,
                    color = colors.destructive,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
            if (info.hint.isNotBlank()) {
                Text(
                    text = "Hint: ${info.hint}",
                    style = VaultTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = spacing.md),
                )
            }
            if (info.recoveryEmail.isNotBlank()) {
                Text(
                    text = "Recovery email on file: ${info.recoveryEmail}",
                    style = VaultTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }

            Spacer(Modifier.height(spacing.xl))
            PillButton(
                text = "Verify",
                onClick = { onVerify(answer.trim()) },
                enabled = answer.isNotBlank(),
            )
        }
    }
}
