package com.appblish.calculatorvault.explore.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.explore.ToolEmptyState
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * Notes list. Each row shows the title and a one-line body preview; the green FAB opens a
 * blank editor and tapping a row opens it for edit. Warm empty state when there are none.
 */
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onNewNote: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotesViewModel = viewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            VaultTopBar(title = "Notes", onBack = onBack)
            if (notes.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ToolEmptyState(
                        title = "No notes yet",
                        message = "Tap + to jot something down. Notes stay locked behind the vault.",
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(notes, key = { it.id }) { note ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenNote(note.id) }
                                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                        ) {
                            Text(
                                text = note.title,
                                style = VaultTheme.typography.bodyLarge,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (note.body.isNotBlank()) {
                                Text(
                                    text = note.body,
                                    style = VaultTheme.typography.labelMedium,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = spacing.xs),
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onNewNote,
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.lg),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New note")
        }
    }
}
