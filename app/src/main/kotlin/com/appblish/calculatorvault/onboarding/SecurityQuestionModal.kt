package com.appblish.calculatorvault.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.appblish.calculatorvault.auth.RecoverySetup
import com.appblish.calculatorvault.auth.SecurityQuestion
import com.appblish.calculatorvault.ui.components.VaultModal
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The "Set security question" modal from the onboarding flow (and reused from Settings). A
 * question picker, the answer, a recovery email, and a password hint. "Save" is enabled
 * once an answer is present; the answer is hashed by the store, never stored in clear.
 * "Cancel" defers — recovery can be configured later.
 */
@Composable
fun SecurityQuestionModal(
    onSave: (RecoverySetup) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var question by remember { mutableStateOf(SecurityQuestion.DEFAULT) }
    var expanded by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    VaultModal(
        title = "Set security question",
        message = "Helps reset your password if you forget it.",
        confirmLabel = "Save",
        dismissLabel = "Cancel",
        confirmEnabled = answer.isNotBlank(),
        onConfirm = { onSave(RecoverySetup(question, answer.trim(), email.trim(), hint.trim())) },
        onDismiss = onCancel,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md)) {
            QuestionPicker(
                selected = question,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onSelect = {
                    question = it
                    expanded = false
                },
            )
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text("Your answer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Recovery email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hint,
                onValueChange = { hint = it },
                label = { Text("Password hint (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun QuestionPicker(
    selected: SecurityQuestion,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (SecurityQuestion) -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Box {
        Surface(
            color = colors.surfaceVariant,
            shape = VaultTheme.shapes.chip,
            modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(true) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.md),
            ) {
                Text(
                    text = selected.prompt,
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = colors.textSecondary,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            SecurityQuestion.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.prompt) },
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}
