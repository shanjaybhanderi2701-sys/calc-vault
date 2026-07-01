package com.appblish.calculatorvault.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appblish.calculatorvault.ui.components.DateGroupedMediaGrid
import com.appblish.calculatorvault.ui.components.FabAction
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.components.MultiSelectActionBar
import com.appblish.calculatorvault.ui.components.SelectionAction
import com.appblish.calculatorvault.ui.components.VaultFab
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * One category screen (Photos/Videos/Audios/Files/Contacts). Photos/Videos/Audios use
 * the date-grouped thumbnail grid; Files/Contacts use a document/contact list. Both
 * support single & multi "pinch" select (long-press to start, tap to toggle), a
 * contextual action bar, a Create-Folder modal, and the green FAB → Hide/Create menu.
 */
@Composable
fun CategoryScreen(
    viewModel: CategoryViewModel,
    onBack: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    var fabExpanded by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }

    val usesGrid =
        state.category == VaultCategory.PHOTOS ||
            state.category == VaultCategory.VIDEOS ||
            state.category == VaultCategory.AUDIOS

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.selectionMode) {
                MultiSelectActionBar(
                    selectedCount = state.selectedIds.size,
                    closeIcon = Icons.Filled.Close,
                    onClose = viewModel::clearSelection,
                    actions =
                        listOf(
                            SelectionAction(Icons.Filled.Share, "Share") { /* share intent — hardening phase */ },
                            SelectionAction(Icons.Filled.Delete, "Delete", destructive = true) {
                                viewModel.recycleSelected()
                            },
                        ),
                )
            } else {
                VaultTopBar(
                    title = state.category.label,
                    subtitle = "${state.items.size} items",
                    onBack = onBack,
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isEmpty -> EmptyCategory(state.category)
                    usesGrid ->
                        DateGroupedMediaGrid(
                            items = state.items.map { it.toMediaItem() },
                            selectionMode = state.selectionMode,
                            selectedIds = state.selectedIds,
                            checkIcon = Icons.Filled.Check,
                            onItemClick = { media -> onItemClicked(state, viewModel, media.id, onOpenItem) },
                            onItemLongPress = { media -> viewModel.startSelection(media.id) },
                        )
                    else ->
                        CategoryList(
                            state = state,
                            onItemClick = { item -> onItemClicked(state, viewModel, item.id, onOpenItem) },
                            onItemLongPress = { item -> viewModel.startSelection(item.id) },
                        )
                }
            }
        }

        if (!state.selectionMode) {
            VaultFab(
                icon = Icons.Filled.Add,
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                actions =
                    listOf(
                        FabAction("Hide ${state.category.label.lowercase()}", Icons.Filled.Add) { onHide() },
                        FabAction("Create folder", Icons.Filled.Add) { showCreateFolder = true },
                    ),
                contentDescription = "Add",
                modifier = Modifier.align(Alignment.BottomEnd).padding(VaultTheme.spacing.lg),
            )
        }
    }

    if (showCreateFolder) {
        CreateFolderModal(
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreateFolder = false
            },
            onDismiss = { showCreateFolder = false },
        )
    }
}

private fun onItemClicked(
    state: CategoryState,
    viewModel: CategoryViewModel,
    itemId: String,
    onOpenItem: (VaultItem) -> Unit,
) {
    if (state.selectionMode) {
        viewModel.toggle(itemId)
    } else {
        state.items.firstOrNull { it.id == itemId }?.let(onOpenItem)
    }
}

private fun VaultItem.toMediaItem(): MediaItem =
    MediaItem(id = id, dateLabel = dateLabel, sortKey = sortKey)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryList(
    state: CategoryState,
    onItemClick: (VaultItem) -> Unit,
    onItemLongPress: (VaultItem) -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.items, key = { it.id }) { item ->
            val selected = item.id in state.selectedIds
            Column(
                modifier =
                    Modifier
                        .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.canvas)
                        .combinedClickable(
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLongPress(item) },
                        )
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
            ) {
                Text(text = item.originalName, style = VaultTheme.typography.bodyLarge, color = colors.textPrimary)
                Text(text = item.dateLabel, style = VaultTheme.typography.labelMedium, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun EmptyCategory(category: VaultCategory) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val cat = category.label.lowercase()
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = category.icon(),
            contentDescription = null,
            tint = category.color(),
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        Text(
            text = "No $cat hidden yet",
            style = VaultTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        Text(
            text = "Tap + to hide $cat from your device — they'll be encrypted here and removed from public storage.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun CreateFolderModal(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    com.appblish.calculatorvault.ui.components.VaultModal(
        title = "Create folder",
        confirmLabel = "Create",
        onConfirm = { onConfirm(name) },
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank(),
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Folder name") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        },
    )
}
