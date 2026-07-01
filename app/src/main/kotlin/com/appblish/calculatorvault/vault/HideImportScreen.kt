package com.appblish.calculatorvault.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appblish.calculatorvault.ui.components.DateGroupedMediaGrid
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * The shared hide/import flow: **album picker → date-grouped multi-select → Hide Now**.
 * Picking an album loads its date-grouped source items; the user pinch-selects (with a
 * select-all affordance) and taps the green **Hide Now** CTA, which encrypts the chosen
 * items into the vault and removes the public originals, then returns to the category.
 */
@Composable
fun HideImportScreen(
    viewModel: HideImportViewModel,
    onBack: () -> Unit,
    onHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    LaunchedEffect(state.done) {
        if (state.done) onHidden()
    }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            VaultTopBar(
                title = "Hide ${state.category.label.lowercase()}",
                subtitle = state.albums.firstOrNull { it.id == state.selectedAlbumId }?.name,
                onBack = onBack,
            )

            if (state.selectedAlbumId == null) {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(state.albums, key = { it.id }) { album ->
                        ListRow(
                            title = album.name,
                            subtitle = "${album.count} items",
                            leadingIcon = state.category.icon(),
                            leadingChipColor = state.category.color(),
                            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
                            onClick = { viewModel.selectAlbum(album.id) },
                        )
                    }
                }
            } else {
                val allSelected =
                    state.sources.isNotEmpty() && state.selectedIds.containsAll(state.sources.map { it.id }.toSet())
                Box(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = viewModel::toggleAll, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(
                            text = if (allSelected) "Clear all" else "Select all",
                            color = colors.accent,
                            style = VaultTheme.typography.labelLarge,
                        )
                    }
                }
                DateGroupedMediaGrid(
                    items = state.sources.map { MediaItem(id = it.id, dateLabel = it.dateLabel, sortKey = it.sortKey) },
                    selectionMode = true,
                    selectedIds = state.selectedIds,
                    checkIcon = Icons.Filled.Check,
                    onItemClick = { viewModel.toggle(it.id) },
                    onItemLongPress = { viewModel.toggle(it.id) },
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = if (state.selectedIds.isEmpty()) "Hide Now" else "Hide Now (${state.selectedIds.size})",
                    onClick = viewModel::hideNow,
                    enabled = state.hideEnabled,
                    modifier = Modifier.padding(spacing.lg),
                )
            }
        }
    }
}
