package com.appblish.calculatorvault.vault.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The *label-editor* dialog family (W2-E design §1.1/§4, xlock J/K parity via APP-218):
 * one composable, two entry points — **"New album"** (create, prefilled "New album") and
 * **"Rename album"** (prefilled with the current name). The field text arrives fully
 * pre-selected so the first keystroke replaces it, with a ✕-clear at the end; CANCEL/OK
 * are plain text buttons (design decision F-1 — this family differs from the verb-named
 * *action* dialogs on purpose).
 *
 * Errors are inline and never dismiss the dialog: empty → "Enter an album name" (OK
 * disabled), duplicate against [existingNames] (case-insensitive) → "An album with this
 * name already exists". OK on an untouched prefill is allowed (creates/keeps that literal
 * name); the caller decides no-op semantics for an unchanged rename.
 *
 * Copy rule (spec §1.2): no path, no "folder", no filesystem language — an album is an
 * encrypted-index label, and this dialog edits exactly that.
 */
@Composable
fun AlbumNameDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var field by remember {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, initialName.length)))
    }
    val trimmed = field.text.trim()
    val lowered = existingNames.mapTo(mutableSetOf()) { it.trim().lowercase() }
    val error =
        when {
            trimmed.isEmpty() -> "Enter an album name"
            trimmed.lowercase() in lowered -> "An album with this name already exists"
            else -> null
        }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.surface, shape = VaultTheme.shapes.card, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(spacing.xl)) {
                Text(text = title, style = VaultTheme.typography.titleLarge, color = colors.textPrimary)
                Spacer(Modifier.size(spacing.lg))
                AlbumNameField(value = field, onValueChange = { field = it }, error = error)
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", style = VaultTheme.typography.labelLarge, color = colors.accent)
                    }
                    TextButton(
                        onClick = { onConfirm(trimmed) },
                        enabled = trimmed.isNotEmpty() && error == null,
                    ) {
                        Text(
                            text = "OK",
                            style = VaultTheme.typography.labelLarge,
                            color = if (trimmed.isNotEmpty() && error == null) colors.accent else colors.textDisabled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The §1.1 album-name input: outlined field with a trailing ✕-clear and the inline error
 * line. Shared by [AlbumNameDialog] and the Move-to sheet's inline create-album row so the
 * prefill/pre-select/✕ behaviour is identical at both entry points.
 */
@Composable
fun AlbumNameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            isError = error != null,
            trailingIcon = {
                if (value.text.isNotEmpty()) {
                    IconButton(onClick = { onValueChange(TextFieldValue("")) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear name",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(
                text = error,
                style = VaultTheme.typography.labelMedium,
                color = colors.destructive,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
