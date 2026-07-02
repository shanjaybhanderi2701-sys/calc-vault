package com.appblish.calculatorvault.explore.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.components.VaultModal
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * Note editor. Opens blank for a new note or pre-filled for an existing one. The top-bar
 * check saves and returns; an existing note also offers a red-guarded delete. Saving a
 * genuinely empty note is a no-op so back-out never litters the list.
 */
@Composable
fun NoteEditorScreen(
    noteId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotesViewModel = viewModel(),
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val existing = remember(noteId) { viewModel.note(noteId) }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember { mutableStateOf(existing?.body.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun saveAndExit() {
        if (title.isNotBlank() || body.isNotBlank()) {
            viewModel.save(existing?.id, title, body)
        }
        onBack()
    }

    val fieldColors =
        TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = colors.accent,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
        )

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(
            title = if (existing == null) "New note" else "Edit note",
            onBack = { saveAndExit() },
            actionIcon = Icons.Filled.Check,
            actionDescription = "Save",
            onAction = { saveAndExit() },
        )
        if (existing != null) {
            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = spacing.sm)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete note", tint = colors.destructive)
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            singleLine = true,
            placeholder = { Text("Title", style = VaultTheme.typography.titleLarge) },
            textStyle = VaultTheme.typography.titleLarge,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            placeholder = { Text("Write something private…") },
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = spacing.md),
        )
    }

    if (confirmDelete && existing != null) {
        VaultModal(
            title = "Delete note?",
            message = "\"${existing.title}\" will be removed. This can't be undone.",
            confirmLabel = "Delete",
            confirmStyle = PillButtonStyle.Destructive,
            onConfirm = {
                viewModel.delete(existing.id)
                confirmDelete = false
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
