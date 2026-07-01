package com.appblish.calculatorvault.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.components.MultiSelectActionBar
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.components.SelectionAction
import com.appblish.calculatorvault.ui.components.VaultModal
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.RecycleBin
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * The recycle bin: soft-deleted items awaiting the 30-day auto-delete window. Each row
 * shows its "N days left"; multi-select drives **Restore** (back to category) and
 * **Delete forever** (irreversible, guarded by a red destructive confirm). Expired
 * entries are already purged by the ViewModel on open.
 */
@Composable
fun RecycleBinScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecycleBinViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }
    val now = remember(state.entries) { viewModel.clock() }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.selectionMode) {
                MultiSelectActionBar(
                    selectedCount = state.selectedIds.size,
                    closeIcon = Icons.Filled.Close,
                    onClose = viewModel::clearSelection,
                    actions =
                        listOf(
                            SelectionAction(Icons.Filled.Refresh, "Restore") { viewModel.restoreSelected() },
                            SelectionAction(Icons.Filled.Delete, "Delete forever", destructive = true) {
                                confirmDelete = true
                            },
                        ),
                )
            } else {
                VaultTopBar(
                    title = "Recycle bin",
                    subtitle = "Auto-deletes after ${RecycleBin.AUTO_DELETE_WINDOW_DAYS} days",
                    onBack = onBack,
                )
            }

            if (state.isEmpty) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { EmptyBin() }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(state.entries, key = { it.item.id }) { entry ->
                        RecycleRow(
                            entry = entry,
                            now = now,
                            selected = entry.item.id in state.selectedIds,
                            onClick = { viewModel.toggle(entry.item.id) },
                            onLongPress = { viewModel.toggle(entry.item.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        VaultModal(
            title = "Delete forever?",
            message = "${state.selectedIds.size} item(s) will be permanently destroyed. This can't be undone.",
            confirmLabel = "Delete",
            confirmStyle = PillButtonStyle.Destructive,
            onConfirm = {
                viewModel.deleteSelectedForever()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleRow(
    entry: RecycleBinEntry,
    now: Long,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val daysLeft = RecycleBin.daysLeft(entry, now)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.canvas)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(VaultTheme.shapes.thumbnail).background(colors.surfaceVariant),
        ) {
            Icon(
                imageVector = entry.item.category.icon(),
                contentDescription = null,
                tint = entry.item.category.color(),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.size(spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.item.originalName, style = VaultTheme.typography.bodyLarge, color = colors.textPrimary)
            Text(
                text = if (daysLeft <= 0L) "Deleting soon" else "$daysLeft days left",
                style = VaultTheme.typography.labelMedium,
                color = if (daysLeft <= 3L) colors.destructive else colors.textSecondary,
            )
        }
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(22.dp).clip(CircleShape).background(colors.accent),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = colors.onAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyBin() {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = colors.textSecondary)
        Text(text = "Recycle bin is empty", style = VaultTheme.typography.titleMedium, color = colors.textPrimary)
        Text(
            text = "Items you delete land here for ${RecycleBin.AUTO_DELETE_WINDOW_DAYS} days, then they're gone.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}
