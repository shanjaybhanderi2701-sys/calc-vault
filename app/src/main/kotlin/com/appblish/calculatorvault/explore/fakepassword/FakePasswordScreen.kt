package com.appblish.calculatorvault.explore.fakepassword

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.explore.FakePasswordState
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.components.VaultModal
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * Fake Password management surface. Explains the decoy concept, lets the user set or change
 * a 4-digit decoy PIN, and toggles it on/off. When enabled, entering this PIN on the
 * calculator is meant to open a separate, empty decoy vault (the calculator-side routing is
 * the Phase 1 auth spine's job; this screen owns the credential + on/off state).
 */
@Composable
fun FakePasswordScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FakePasswordViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var showEditor by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = "Fake Password", onBack = onBack)

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .padding(top = spacing.lg)
                    .align(Alignment.CenterHorizontally)
                    .size(88.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.accent.copy(alpha = 0.16f)),
        ) {
            Icon(Icons.Filled.Face, contentDescription = null, tint = colors.accent, modifier = Modifier.size(40.dp))
        }
        Text(
            text = "A safe way to open up under pressure",
            style = VaultTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.lg, start = spacing.lg, end = spacing.lg),
        )
        Text(
            text =
                "Set a decoy 4-digit PIN. Typed on the calculator it opens a separate, empty vault — " +
                    "so if someone forces you to unlock, your real hidden content stays invisible.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm, start = spacing.lg, end = spacing.lg),
        )

        Column(modifier = Modifier.padding(top = spacing.lg)) {
            ListRow(
                title = if (state.configured) "Decoy PIN" else "No decoy PIN set",
                subtitle = if (state.configured) "•••• · tap to change" else "Tap to create one",
                leadingIcon = Icons.Filled.Face,
                leadingChipColor = colors.accent,
                trailing = if (state.hint.isNotBlank()) RowTrailing.Badge("hint") else RowTrailing.None,
                onClick = { showEditor = true },
            )
            if (state.configured) {
                ListRow(
                    title = "Enable decoy PIN",
                    subtitle = "Accept this PIN at the calculator",
                    leadingIcon = Icons.Filled.Face,
                    leadingChipColor = colors.accent,
                    trailing = RowTrailing.Toggle(checked = state.enabled, onCheckedChange = viewModel::setEnabled),
                )
            }
        }

        PillButton(
            text = if (state.configured) "Change decoy PIN" else "Set decoy PIN",
            onClick = { showEditor = true },
            modifier = Modifier.padding(spacing.lg),
        )
    }

    if (showEditor) {
        DecoyPinEditor(
            initialHint = state.hint,
            isValid = viewModel::isValidPin,
            onSave = { pin, hint ->
                viewModel.save(pin, hint)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun DecoyPinEditor(
    initialHint: String,
    isValid: (String) -> Boolean,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = VaultTheme.spacing
    var pin by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf(initialHint) }

    VaultModal(
        title = "Decoy PIN",
        message = "Choose a 4-digit PIN different from your real one.",
        confirmLabel = "Save",
        confirmEnabled = isValid(pin),
        onConfirm = { onSave(pin, hint) },
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                    singleLine = true,
                    label = { Text("4-digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it },
                    singleLine = true,
                    label = { Text("Hint (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** Convenience for previews/tests: the default (unset) decoy state. */
internal val emptyFakePassword = FakePasswordState()
