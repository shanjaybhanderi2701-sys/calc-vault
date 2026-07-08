package com.appblish.calculatorvault.vault

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appblish.calculatorvault.ui.components.DateGroupedMediaGrid
import com.appblish.calculatorvault.ui.components.DeleteChoiceDialog
import com.appblish.calculatorvault.ui.components.FastScrollbar
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.components.MultiSelectActionBar
import com.appblish.calculatorvault.ui.components.SelectionAction
import com.appblish.calculatorvault.ui.components.VaultModal
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * One category screen (Photos/Videos/Audios/Files/Contacts), folder-grid-first per the
 * Phase-1 design sign-off (APP-224/APP-225, S10–S17): the root shows ONLY the category's
 * folders as a 3-column tile grid (cover thumbnail, name, count) plus a "+" tile into the
 * hide flow, with a top-right ↑↓ folder sort (S16). Tapping a folder opens its items in
 * the date-grouped multi-select grid (S17) — root ↔ folder is internal ViewModel state, so
 * system back walks folder → grid → pop. Multi-select offers Share / Restore (D-3 summary
 * snackbar) / Delete (shared D-4 delete-choice dialog); the FAB pops the S11 bubble menu
 * (Create Folder / Hide …).
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
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var fabExpanded by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var showDeleteChoice by remember { mutableStateOf(false) }

    // Back inside a folder clears the selection first, then returns to the folder grid;
    // only at the root does back propagate to the nav host and pop the screen.
    BackHandler(enabled = state.selectionMode || state.inFolder) {
        if (state.selectionMode) viewModel.clearSelection() else viewModel.closeFolder()
    }

    // D-3 (generalized for P2-3): one snackbar per operation — restore, recycle, delete,
    // and the hide picker's "N hidden" hand-off — never silent. ~6s (Long) so the fallback
    // destination is readable; consumed even if the effect is cancelled mid-show.
    LaunchedEffect(state.opNotice) {
        val notice = state.opNotice ?: return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(notice, duration = SnackbarDuration.Long)
        } finally {
            viewModel.consumeOpNotice()
        }
    }

    val usesGrid =
        state.category == VaultCategory.PHOTOS ||
            state.category == VaultCategory.VIDEOS ||
            state.category == VaultCategory.AUDIOS

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                state.selectionMode ->
                    MultiSelectActionBar(
                        selectedCount = state.selectedIds.size,
                        closeIcon = Icons.Filled.Close,
                        onClose = viewModel::clearSelection,
                        actions =
                            listOf(
                                SelectionAction(Icons.Filled.Share, "Share") { /* share intent — hardening phase */ },
                                // Restore (spec §8 vocabulary): decrypt back to public
                                // storage so the item returns to the gallery.
                                SelectionAction(Icons.Filled.Refresh, "Restore") {
                                    viewModel.restoreSelected()
                                },
                                // D-4: Delete opens the shared choice dialog (bin vs forever).
                                SelectionAction(Icons.Filled.Delete, "Delete", destructive = true) {
                                    showDeleteChoice = true
                                },
                            ),
                    )
                state.inFolder ->
                    VaultTopBar(
                        title = state.openFolderTitle,
                        subtitle = "${state.folderItems.size} items",
                        onBack = viewModel::closeFolder,
                    )
                else ->
                    CategoryRootHeader(
                        title = state.category.label,
                        sort = state.folderSort,
                        onBack = onBack,
                        onSortSelect = viewModel::setFolderSort,
                    )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // S10 root: folders only — items live inside their folder screens.
                    !state.inFolder ->
                        FolderTileGrid(
                            tiles = state.folderTiles,
                            category = state.category,
                            onOpen = viewModel::openFolder,
                            onAdd = onHide,
                            loadCover = { itemId -> viewModel.thumbnail(context, itemId) },
                        )
                    // S17 empty folder: per-category "No Hidden … Yet" with the hide hint.
                    state.folderItems.isEmpty() -> EmptyFolderState(state.category)
                    usesGrid -> {
                        val mediaGridState = rememberLazyGridState()
                        DateGroupedMediaGrid(
                            items = state.folderItems.map { it.toMediaItem() },
                            selectionMode = state.selectionMode,
                            selectedIds = state.selectedIds,
                            checkIcon = Icons.Filled.Check,
                            onItemClick = { media -> onItemClicked(state, viewModel, media.id, onOpenItem) },
                            onItemLongPress = { media -> viewModel.startSelection(media.id) },
                            loadThumbnail = { media -> viewModel.thumbnail(context, media.id) },
                            state = mediaGridState,
                        )
                        FastScrollbar(
                            state = mediaGridState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                    else ->
                        CategoryList(
                            items = state.folderItems,
                            selectedIds = state.selectedIds,
                            onItemClick = { item -> onItemClicked(state, viewModel, item.id, onOpenItem) },
                            onItemLongPress = { item -> viewModel.startSelection(item.id) },
                        )
                }
            }
        }

        if (!state.selectionMode) {
            CategoryFabMenu(
                category = state.category,
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                onCreateFolder = { showCreateFolder = true },
                onHide = onHide,
                modifier = Modifier.align(Alignment.BottomEnd).padding(VaultTheme.spacing.lg),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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

    // D-4: every Delete routes through the shared choice dialog — Recycle Bin is the safe
    // default, permanent delete is the explicit destructive choice (no second confirm).
    if (showDeleteChoice) {
        DeleteChoiceDialog(
            itemCount = state.selectedIds.size,
            onMoveToRecycleBin = {
                viewModel.recycleSelected()
                showDeleteChoice = false
            },
            onDeletePermanently = {
                viewModel.deleteSelectedForever()
                showDeleteChoice = false
            },
            onDismiss = { showDeleteChoice = false },
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
        state.folderItems.firstOrNull { it.id == itemId }?.let(onOpenItem)
    }
}

private fun VaultItem.toMediaItem(): MediaItem = MediaItem(id = id, dateLabel = dateLabel, sortKey = sortKey)

/** The per-category FAB hide label from the S11 menu ("Hide Image" / "Hide Video" / …). */
private fun hideActionLabel(category: VaultCategory): String =
    when (category) {
        VaultCategory.PHOTOS -> "Hide Image"
        VaultCategory.VIDEOS -> "Hide Video"
        VaultCategory.AUDIOS -> "Hide Audio"
        VaultCategory.FILES -> "Hide File"
        VaultCategory.CONTACTS -> "Hide Contact"
    }

/**
 * Root header (S10/S16): back chevron + "‹ {Category}" title + the trailing ↑↓ folder-sort
 * control. Local to this screen rather than [VaultTopBar] because the sort trigger is a
 * two-glyph control with an anchored menu, not the top bar's single action icon.
 */
@Composable
private fun CategoryRootHeader(
    title: String,
    sort: FolderSort,
    onBack: () -> Unit,
    onSortSelect: (FolderSort) -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(end = spacing.sm, top = spacing.sm, bottom = spacing.sm),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = colors.textPrimary)
        }
        Text(
            text = title,
            style = VaultTheme.typography.titleLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        FolderSortMenu(current = sort, onSelect = onSortSelect)
    }
}

/** S16 sort control: a top-right ↑↓ glyph pair opening the folder-sort menu. */
@Composable
private fun FolderSortMenu(
    current: FolderSort,
    onSelect: (FolderSort) -> Unit,
) {
    val colors = VaultTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Sort folders",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FolderSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = sort.label,
                            color = if (sort == current) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        onSelect(sort)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * S10 root grid: 3 columns of large rounded folder tiles (cover thumbnail from the
 * newest contained item, name, count) plus the trailing "+" tile into the hide flow.
 */
@Composable
private fun FolderTileGrid(
    tiles: List<CategoryFolderTile>,
    category: VaultCategory,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    loadCover: suspend (String) -> ImageBitmap?,
) {
    val spacing = VaultTheme.spacing
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(horizontal = spacing.lg),
        contentPadding = PaddingValues(vertical = spacing.md),
    ) {
        items(tiles, key = { it.id }) { tile ->
            FolderTileCard(
                tile = tile,
                category = category,
                onClick = { onOpen(tile.id) },
                loadCover = loadCover,
            )
        }
        item(key = "__add__") {
            AddFolderTile(onClick = onAdd)
        }
    }
}

/** One folder tile: rounded square cover (placeholder glyph when empty), name, count. */
@Composable
private fun FolderTileCard(
    tile: CategoryFolderTile,
    category: VaultCategory,
    onClick: () -> Unit,
    loadCover: suspend (String) -> ImageBitmap?,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val cover: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, tile.cover?.id) {
        value = tile.cover?.let { loadCover(it.id) }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(spacing.xs).clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(VaultTheme.shapes.thumbnail)
                    .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            cover?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: Icon(
                imageVector = category.icon(),
                contentDescription = null,
                tint = category.color(),
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            text = tile.name,
            style = VaultTheme.typography.labelLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Text(
            text = "${tile.itemCount}",
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
    }
}

/** The trailing "+" tile (S10): same footprint as a folder tile, opens the hide flow. */
@Composable
private fun AddFolderTile(onClick: () -> Unit) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(spacing.xs).clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(VaultTheme.shapes.thumbnail)
                    .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Hide new items",
                tint = colors.accent,
                modifier = Modifier.size(32.dp),
            )
        }
        // Same label slot as a folder tile's name, so tile bottoms and label baselines
        // align across the grid row (APP-234 spec §2.4).
        Text(
            text = "Add",
            style = VaultTheme.typography.labelLarge,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryList(
    items: List<VaultItem>,
    selectedIds: Set<String>,
    onItemClick: (VaultItem) -> Unit,
    onItemLongPress: (VaultItem) -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { item ->
                val selected = item.id in selectedIds
                Column(
                    modifier =
                        Modifier
                            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.canvas)
                            .combinedClickable(
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemLongPress(item) },
                            ).padding(horizontal = spacing.lg, vertical = spacing.md),
                ) {
                    Text(text = item.originalName, style = VaultTheme.typography.bodyLarge, color = colors.textPrimary)
                    Text(text = item.dateLabel, style = VaultTheme.typography.labelMedium, color = colors.textSecondary)
                }
            }
        }
        // P2-2: draggable fast-scroll for long folders (renders only past ~30 items).
        FastScrollbar(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * S17 empty-folder state: "No Hidden Photos Yet" (per-category noun) over the existing
 * helper line and the category glyph. The FAB below keeps the media-add affordance.
 */
@Composable
private fun EmptyFolderState(category: VaultCategory) {
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
            modifier = Modifier.size(40.dp).padding(bottom = spacing.sm),
        )
        Text(
            text = "No Hidden ${category.label} Yet",
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

/**
 * S11 FAB action menu: the solid green FAB flips its `+` to an `×` and pops a dark
 * speech-bubble card anchored above it, with two glyph rows — "Create Folder" and the
 * per-category hide action. The bubble's pointer tail is approximated by proximity (no
 * bespoke shape asset); glyphs come from material-icons-core, the only icon artifact.
 */
@Composable
private fun CategoryFabMenu(
    category: VaultCategory,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCreateFolder: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = modifier,
    ) {
        AnimatedVisibility(visible = expanded) {
            Surface(
                color = colors.surfaceVariant,
                shape = VaultTheme.shapes.card,
                shadowElevation = 6.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = spacing.xs)) {
                    FabMenuRow(
                        label = "Create Folder",
                        icon = Icons.Filled.Add,
                        onClick = {
                            onExpandedChange(false)
                            onCreateFolder()
                        },
                    )
                    FabMenuRow(
                        label = hideActionLabel(category),
                        icon = category.icon(),
                        onClick = {
                            onExpandedChange(false)
                            onHide()
                        },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = if (expanded) "Close menu" else "Add",
                // The 45° roll reads the `+` as a close `×` while the menu is up.
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = if (expanded) 45f else 0f },
            )
        }
    }
}

/** One glyph row inside the S11 bubble menu. */
@Composable
private fun FabMenuRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = VaultTheme.typography.labelLarge,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = spacing.md),
        )
    }
}

/** S12 create-folder dialog: "Create a new folder" / "Enter folder name" / Cancel · Create. */
@Composable
private fun CreateFolderModal(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    VaultModal(
        title = "Create a new folder",
        confirmLabel = "Create",
        onConfirm = { onConfirm(name) },
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank(),
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Enter folder name") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        },
    )
}
