package com.appblish.calculatorvault.vault

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appblish.calculatorvault.ui.components.DateGroupedMediaGrid
import com.appblish.calculatorvault.ui.components.DeleteChoiceDialog
import com.appblish.calculatorvault.ui.components.FastScrollbar
import com.appblish.calculatorvault.ui.components.GridDragSelectCallbacks
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.components.MultiSelectActionBar
import com.appblish.calculatorvault.ui.components.SelectionAction
import com.appblish.calculatorvault.ui.components.SelectionActionTray
import com.appblish.calculatorvault.ui.components.SelectionOverflowItem
import com.appblish.calculatorvault.ui.components.pinchColumns
import com.appblish.calculatorvault.ui.components.rememberPinchColumnsState
import com.appblish.calculatorvault.ui.theme.VaultActionIcons
import com.appblish.calculatorvault.ui.theme.VaultGridTokens
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.actions.AlbumNameDialog
import com.appblish.calculatorvault.vault.actions.AlbumOption
import com.appblish.calculatorvault.vault.actions.AlbumProperties
import com.appblish.calculatorvault.vault.actions.ChosenFolder
import com.appblish.calculatorvault.vault.actions.DeleteDialog
import com.appblish.calculatorvault.vault.actions.DeleteStep
import com.appblish.calculatorvault.vault.actions.MoveToSheet
import com.appblish.calculatorvault.vault.actions.NEW_ALBUM_PREFILL
import com.appblish.calculatorvault.vault.actions.PhotoProperties
import com.appblish.calculatorvault.vault.actions.PropertyDialog
import com.appblish.calculatorvault.vault.actions.UnhideChoice
import com.appblish.calculatorvault.vault.actions.UnhideDialog
import com.appblish.calculatorvault.vault.actions.chosenFolderFrom
import com.appblish.calculatorvault.vault.model.SortKey
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.model.sortItems
import com.appblish.calculatorvault.vault.ui.SortSheet
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * One category screen (Photos/Videos/Audios/Files/Contacts), folder-grid-first per the
 * Phase-1 design sign-off (APP-224/APP-225, S10–S17): the root shows ONLY the category's
 * folders as a 3-column tile grid (cover thumbnail, name, count) plus a "+" tile into the
 * hide flow, with a top-right ↑↓ folder sort (S16). Tapping a folder opens its items in
 * the date-grouped multi-select grid (S17) — root ↔ folder is internal ViewModel state, so
 * system back walks folder → grid → pop. Multi-select (W1-E3) enters on long-press, extends
 * by tap-toggle, long-press-drag range select and Select All, and offers the bulk actions
 * Move / Unhide / Delete (D-3 summary snackbar, shared D-4 delete-choice dialog); the FAB
 * pops the S11 bubble menu (Create album / Hide …). Album multi-select (W2-E §9) enters
 * on a root-tile long-press with the album bulk set: Rename (N=1) / Move / Unhide /
 * Delete / Property.
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
    var showMoveSheet by remember { mutableStateOf(false) }
    var showUnhideDialog by remember { mutableStateOf(false) }
    // Album-level action surfaces (W2-E §4–§8), reached from the root grid's album
    // selection bar. The delete flow tracks its own 2-step state; null == closed.
    var showRenameAlbum by remember { mutableStateOf(false) }
    var showAlbumMoveSheet by remember { mutableStateOf(false) }
    var showAlbumUnhideDialog by remember { mutableStateOf(false) }
    var showAlbumProperty by remember { mutableStateOf(false) }
    var albumDeleteStep by remember { mutableStateOf<DeleteStep?>(null) }
    // W3-E: the Sort-by sheet (§7) and the album whose Choose-cover picker is open (§6).
    var showSortSheet by remember { mutableStateOf(false) }
    var chooseCoverAlbumId by remember { mutableStateOf<String?>(null) }
    // APP-293 item 5: Property in item multi-select (aggregate for many, detail for one).
    var showProperty by remember { mutableStateOf(false) }
    // APP-293 item 6: "Unhide album" from the open album's ⋯ More menu.
    var showOpenAlbumUnhide by remember { mutableStateOf(false) }
    var unhideChoice by remember { mutableStateOf(UnhideChoice.ORIGINAL) }
    var chosenUnhideFolder by remember { mutableStateOf<ChosenFolder?>(null) }
    val unhideFolderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val picked = uri?.let { chosenFolderFrom(context, it) }
            if (picked != null) {
                chosenUnhideFolder = picked
                unhideChoice = UnhideChoice.CHOSEN
            } else if (chosenUnhideFolder == null) {
                // Backed out with nothing ever picked — fall back to the safe default choice.
                unhideChoice = UnhideChoice.ORIGINAL
            }
        }

    // Back inside a folder clears the selection first, then returns to the folder grid;
    // only at the root (with no album selection live) does back propagate to the nav host.
    BackHandler(enabled = state.selectionMode || state.albumSelectionMode || state.inFolder) {
        when {
            state.selectionMode -> viewModel.clearSelection()
            state.albumSelectionMode -> viewModel.clearAlbumSelection()
            else -> viewModel.closeFolder()
        }
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

    // §6 Choose-cover picker: a full-screen surface with one job — it replaces the whole
    // category screen while open; `‹`/system back cancels without a write.
    chooseCoverAlbumId?.let { albumId ->
        val album = state.albums.firstOrNull { it.id == albumId }
        if (album == null) {
            chooseCoverAlbumId = null
        } else {
            // The picker follows the album's own display order (§7): its per-album
            // override when set, else the vault-wide photo sort the state carries at root.
            val members =
                sortItems(
                    state.items.filter { it.folderId == albumId },
                    album.photoSortOverride ?: state.photoSort,
                )
            ChooseCoverScreen(
                items = members,
                currentCoverId = album.coverItemId ?: members.maxByOrNull { it.sortKey }?.id,
                onConfirm = { itemId ->
                    viewModel.setAlbumCover(albumId, itemId)
                    chooseCoverAlbumId = null
                },
                onCancel = { chooseCoverAlbumId = null },
                loadThumbnail = { item -> viewModel.thumbnail(context, item.id) },
                modifier = modifier,
            )
            return
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                state.selectionMode ->
                    // W1-E3 selection header: count + Select All. The bulk actions live in
                    // the labeled bottom tray (APP-293 item 13) — the viewer-bar pattern.
                    MultiSelectActionBar(
                        selectedCount = state.selectedIds.size,
                        closeIcon = Icons.Filled.Close,
                        onClose = viewModel::clearSelection,
                        actions =
                            listOf(
                                SelectionAction(Icons.Filled.CheckCircle, "Select all") {
                                    viewModel.selectAllInFolder()
                                },
                            ),
                    )
                state.albumSelectionMode ->
                    // W2-E §9 album selection header: count + Select All. Album bulk
                    // actions live in the labeled bottom tray (APP-293 item 13).
                    MultiSelectActionBar(
                        selectedCount = state.selectedAlbumIds.size,
                        closeIcon = Icons.Filled.Close,
                        onClose = viewModel::clearAlbumSelection,
                        actions =
                            listOf(
                                SelectionAction(Icons.Filled.CheckCircle, "Select all") {
                                    viewModel.selectAllAlbums()
                                },
                            ),
                    )
                state.inFolder ->
                    CategoryHeader(
                        title = state.openFolderTitle,
                        subtitle = "${state.folderItems.size} items",
                        showSort = state.folderItems.isNotEmpty(),
                        onBack = viewModel::closeFolder,
                        onSortClick = { showSortSheet = true },
                        // APP-293 item 6: the album grid's ⋯ More menu — Unhide album.
                        // The synthetic "Recent" pseudo-folder is not an album.
                        menu =
                            if (state.openFolderId != CategoryState.RECENT_FOLDER_ID &&
                                state.folderItems.isNotEmpty()
                            ) {
                                listOf(
                                    SelectionOverflowItem("Unhide album") {
                                        unhideChoice = UnhideChoice.ORIGINAL
                                        chosenUnhideFolder = null
                                        showOpenAlbumUnhide = true
                                    },
                                )
                            } else {
                                emptyList()
                            },
                    )
                else ->
                    CategoryHeader(
                        title = state.category.label,
                        showSort = state.folderTiles.isNotEmpty(),
                        onBack = onBack,
                        onSortClick = { showSortSheet = true },
                    )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // S10 root: albums only — items live inside their album screens. Long-
                    // press enters album selection (W2-E §9); taps then toggle membership.
                    !state.inFolder ->
                        FolderTileGrid(
                            tiles = state.folderTiles,
                            category = state.category,
                            selectionMode = state.albumSelectionMode,
                            selectedIds = state.selectedAlbumIds,
                            onOpen = { id -> if (!viewModel.tappedAlbum(id)) viewModel.openFolder(id) },
                            onLongPress = viewModel::startAlbumSelection,
                            onAdd = onHide,
                            loadCover = { itemId -> viewModel.thumbnail(context, itemId) },
                        )
                    // S17 empty folder: per-category "No Hidden … Yet" with the hide hint.
                    state.folderItems.isEmpty() -> EmptyFolderState(state.category)
                    usesGrid -> {
                        val mediaGridState = rememberLazyGridState()
                        // APP-293 item 10: two-finger pinch rescales the photo grid
                        // fluidly between 2 and 5 columns — no snap.
                        val pinch = rememberPinchColumnsState(initialColumns = 3, minColumns = 2, maxColumns = 5)
                        DateGroupedMediaGrid(
                            items = state.folderItems.map { it.toMediaItem() },
                            selectionMode = state.selectionMode,
                            selectedIds = state.selectedIds,
                            checkIcon = Icons.Filled.Check,
                            onItemClick = { media -> viewModel.tappedItem(media.id)?.let(onOpenItem) },
                            onItemLongPress = { media -> viewModel.startSelection(media.id) },
                            loadThumbnail = { media -> viewModel.thumbnail(context, media.id) },
                            columns = pinch.columns,
                            modifier = Modifier.pinchColumns(pinch),
                            state = mediaGridState,
                            // W3-E §7: the grid renders folderItems' sorted order exactly;
                            // hide-date section headers would lie under Name/Size/Date-
                            // taken orderings, so the album grid is flat.
                            groupByDate = false,
                            // W1-E3: long-press-drag sweeps a display-order range into the
                            // selection; dragging back releases what this gesture added.
                            dragSelect =
                                GridDragSelectCallbacks(
                                    onDragStart = viewModel::beginDragSelect,
                                    onDragOver = viewModel::dragSelectOver,
                                    onDragEnd = viewModel::endDragSelect,
                                ),
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
                            onItemClick = { item -> viewModel.tappedItem(item.id)?.let(onOpenItem) },
                            onItemLongPress = { item -> viewModel.startSelection(item.id) },
                        )
                }
            }
        }

        // The FAB hides in either selection mode — creating an album mid-selection is a
        // mode error (design §9).
        if (!state.selectionMode && !state.albumSelectionMode) {
            CategoryFabMenu(
                category = state.category,
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                onCreateFolder = { showCreateFolder = true },
                onHide = onHide,
                modifier = Modifier.align(Alignment.BottomEnd).padding(VaultTheme.spacing.lg),
            )
        }

        // APP-293 item 13: the labeled multi-select bottom tray — primaries up front
        // (Unhide · Move · Delete; Share joins via the sibling Share issue), the rest in
        // More — mirroring the viewer bottom bar. One tray per selection mode.
        if (state.selectionMode) {
            SelectionActionTray(
                actions =
                    listOf(
                        // §7: bulk unhide — destination dialog, honest summary.
                        SelectionAction(VaultActionIcons.Unhide, "Unhide") {
                            unhideChoice = UnhideChoice.ORIGINAL
                            chosenUnhideFolder = null
                            showUnhideDialog = true
                        },
                        // §6: bulk move — same Move-to sheet as the viewer's action.
                        SelectionAction(VaultActionIcons.MoveTo, "Move") {
                            showMoveSheet = true
                        },
                        // D-4: Delete opens the shared choice dialog (bin vs forever).
                        SelectionAction(Icons.Filled.Delete, "Delete", destructive = true) {
                            showDeleteChoice = true
                        },
                    ),
                overflow =
                    buildList {
                        // APP-293 item 5: Property — aggregate for many, detail for one.
                        add(SelectionOverflowItem("Property") { showProperty = true })
                        // W3-E §5: Change cover photo, N=1 only, real albums only.
                        if (state.selectedIds.size == 1 &&
                            state.openFolderId != null &&
                            state.openFolderId != CategoryState.RECENT_FOLDER_ID
                        ) {
                            add(
                                SelectionOverflowItem("Change cover photo") {
                                    viewModel.setCoverFromSelection()
                                },
                            )
                        }
                    },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (state.albumSelectionMode) {
            val singleTile = state.selectedAlbumTiles.singleOrNull()
            SelectionActionTray(
                actions =
                    buildList {
                        add(
                            // Disabled-by-no-op when the selection holds zero photos
                            // (design §6: nothing to unhide — no dialog).
                            SelectionAction(VaultActionIcons.Unhide, "Unhide") {
                                if (state.selectedAlbumItemCount > 0) {
                                    unhideChoice = UnhideChoice.ORIGINAL
                                    chosenUnhideFolder = null
                                    showAlbumUnhideDialog = true
                                }
                            },
                        )
                        add(SelectionAction(VaultActionIcons.MoveTo, "Move") { showAlbumMoveSheet = true })
                        add(
                            SelectionAction(Icons.Filled.Delete, "Delete", destructive = true) {
                                albumDeleteStep = DeleteStep.CHOICE
                            },
                        )
                        // At N=1 Property lives in the ⋯ More menu with the identity
                        // actions; direct only for the multi-album aggregate view.
                        if (state.selectedAlbumIds.size > 1) {
                            add(SelectionAction(Icons.Filled.Info, "Property") { showAlbumProperty = true })
                        }
                    },
                overflow =
                    singleTile?.let { tile ->
                        buildList {
                            add(SelectionOverflowItem("Rename") { showRenameAlbum = true })
                            add(
                                SelectionOverflowItem(if (tile.pinned) "Unpin album" else "Pin album") {
                                    viewModel.togglePinSelectedAlbum()
                                },
                            )
                            // Hidden for an empty album — nothing to choose (§6).
                            if (tile.itemCount > 0) {
                                add(
                                    SelectionOverflowItem("Change cover photo") {
                                        chooseCoverAlbumId = tile.id
                                        viewModel.clearAlbumSelection()
                                    },
                                )
                            }
                            add(SelectionOverflowItem("Property") { showAlbumProperty = true })
                        }
                    } ?: emptyList(),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // W3-E §7 Sort-by sheet: album keys at the root, photo keys (+ the "This album only"
    // override checkbox on real albums) inside an open album. Live application — every
    // tap lands on the persisted choice and the grid re-sorts behind the sheet.
    if (showSortSheet) {
        if (state.inFolder) {
            SortSheet(
                keys = SortKey.PHOTO_KEYS,
                current = state.photoSort,
                onSortChange = viewModel::setPhotoSort,
                onDismiss = { showSortSheet = false },
                thisAlbumOnly =
                    if (state.openFolderId != CategoryState.RECENT_FOLDER_ID) state.photoSortOverridden else null,
                onThisAlbumOnlyChange = viewModel::setPhotoSortOverride,
            )
        } else {
            SortSheet(
                keys = SortKey.ALBUM_KEYS,
                current = state.albumSort,
                onSortChange = viewModel::setAlbumSort,
                onDismiss = { showSortSheet = false },
            )
        }
    }

    // §1.1 create-album dialog (APP-218 fold-in): "New album" prefilled + pre-selected,
    // ✕-clear, CANCEL/OK, inline empty/duplicate errors.
    if (showCreateFolder) {
        AlbumNameDialog(
            title = "New album",
            initialName = NEW_ALBUM_PREFILL,
            existingNames = albumNames(state),
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreateFolder = false
            },
            onDismiss = { showCreateFolder = false },
        )
    }

    // §4 rename-album dialog: same label-editor family, prefilled with the current name;
    // an unchanged name dismisses as a no-op (no error, no write).
    if (showRenameAlbum) {
        val tile = state.selectedAlbumTiles.singleOrNull()
        if (tile == null) {
            showRenameAlbum = false
        } else {
            AlbumNameDialog(
                title = "Rename album",
                initialName = tile.name,
                existingNames = albumNames(state) - tile.name,
                onConfirm = { name ->
                    if (name != tile.name) viewModel.renameAlbum(tile.id, name)
                    viewModel.clearAlbumSelection()
                    showRenameAlbum = false
                },
                onDismiss = { showRenameAlbum = false },
            )
        }
    }

    // §5 album move-to sheet: merge the selected albums' contents into a target album.
    // Sources are disabled with a "This album" chip; the merge consequence is stated the
    // moment a target is picked, before confirm.
    if (showAlbumMoveSheet) {
        val selectedTiles = state.selectedAlbumTiles
        MoveToSheet(
            itemCount = state.selectedAlbumItemCount,
            albums =
                state.folderTiles
                    .filterNot { it.id == CategoryState.RECENT_FOLDER_ID }
                    .map { AlbumOption(id = it.id, name = it.name, count = it.itemCount) },
            currentFolderId = null,
            title =
                if (selectedTiles.size == 1) {
                    "Move \"${selectedTiles.single().name}\" to…"
                } else {
                    "Move ${selectedTiles.size} albums to…"
                },
            disabledIds = selectedTiles.mapTo(mutableSetOf<String?>()) { it.id },
            disabledBadge = "This album",
            noteForTarget = { target -> albumMergeNote(selectedTiles, target.name) },
            onDismiss = { showAlbumMoveSheet = false },
            onCreateFolder = viewModel::createFolder,
            onMove = { folderId ->
                if (folderId != null) viewModel.moveSelectedAlbums(folderId)
                showAlbumMoveSheet = false
            },
        )
    }

    // §6 album unhide dialog: plural "Original locations" semantics — each photo returns
    // to its own path — with the always-visible per-file fallback promise.
    if (showAlbumUnhideDialog) {
        val selectedTiles = state.selectedAlbumTiles
        val photoCount = state.selectedAlbumItemCount
        UnhideDialog(
            itemCount = photoCount,
            originalPath = null,
            choice = unhideChoice,
            chosenFolder = chosenUnhideFolder,
            title =
                if (selectedTiles.size == 1) {
                    "Unhide \"${selectedTiles.single().name}\""
                } else {
                    "Unhide ${selectedTiles.size} albums"
                },
            bodyText =
                "${if (photoCount == 1) "1 photo" else "$photoCount photos"} will leave the vault. " +
                    "Where should we put them?",
            originalTitle = "Original locations",
            originalSubtitle = "Each photo returns to where it came from",
            fallbackNote = "If an original folder isn't available, we'll save those photos to Downloads and tell you.",
            onChoiceChange = { unhideChoice = it },
            onPickFolder = { unhideFolderPicker.launch(null) },
            onConfirm = { destination ->
                viewModel.unhideSelectedAlbums(destination)
                showAlbumUnhideDialog = false
            },
            onDismiss = { showAlbumUnhideDialog = false },
        )
    }

    // §7 album delete — 2-step: Bin (safe default; contents stay encrypted and the album
    // grouping is kept for a whole-album restore) vs Permanent (secure wipe) behind a
    // second, Error-tinted confirm. Copy spells out album + contents so "delete the
    // album" is never read as "the photos survive somewhere".
    albumDeleteStep?.let { step ->
        val selectedTiles = state.selectedAlbumTiles
        val photoCount = state.selectedAlbumItemCount
        DeleteDialog(
            itemCount = photoCount,
            step = step,
            choiceTitle =
                if (selectedTiles.size == 1) {
                    "Delete \"${selectedTiles.single().name}\"?"
                } else {
                    "Delete ${selectedTiles.size} albums?"
                },
            choiceMessage = albumDeleteChoiceMessage(selectedTiles.size, photoCount),
            permanentBody = albumDeletePermanentBody(selectedTiles, photoCount),
            onMoveToBin = {
                viewModel.recycleSelectedAlbums()
                albumDeleteStep = null
            },
            onChoosePermanent = { albumDeleteStep = DeleteStep.CONFIRM_PERMANENT },
            onConfirmPermanent = {
                viewModel.deleteSelectedAlbumsForever()
                albumDeleteStep = null
            },
            onDismiss = { albumDeleteStep = null },
        )
    }

    // §8 album property dialog: every value from the encrypted index — zero decryption.
    if (showAlbumProperty) {
        val selectedAlbums = state.albums.filter { it.id in state.selectedAlbumIds }
        val itemsByFolder = state.items.groupBy { it.folderId }
        PropertyDialog(
            title =
                if (selectedAlbums.size == 1) "Album details" else "Details — ${selectedAlbums.size} albums",
            rows =
                if (selectedAlbums.size == 1) {
                    AlbumProperties.rows(selectedAlbums.single(), itemsByFolder[selectedAlbums.single().id].orEmpty())
                } else {
                    AlbumProperties.aggregateRows(
                        selectedAlbums,
                        selectedAlbums.associate { it.id to itemsByFolder[it.id].orEmpty() },
                    )
                },
            onDismiss = { showAlbumProperty = false },
        )
    }

    // APP-293 item 5: Property over the item selection — single-item detail rows at N=1
    // (same builder as the viewer's §9 dialog), index-only aggregate rows at N>1.
    if (showProperty) {
        val selectedItems = state.folderItems.filter { it.id in state.selectedIds }
        if (selectedItems.isEmpty()) {
            showProperty = false
        } else {
            PropertyDialog(
                title = if (selectedItems.size == 1) "Details" else "Details — ${selectedItems.size} items",
                rows =
                    selectedItems.singleOrNull()?.let { single ->
                        PhotoProperties.rows(
                            single,
                            state.openFolderTitle.takeUnless {
                                state.openFolderId == CategoryState.RECENT_FOLDER_ID
                            },
                        )
                    } ?: PhotoProperties.aggregateRows(selectedItems),
                onDismiss = { showProperty = false },
            )
        }
    }

    // APP-293 item 6: whole-album unhide from the open album's ⋯ More menu — the same
    // destination dialog + per-file fallback contract as the album-selection path.
    if (showOpenAlbumUnhide) {
        val albumTitle = state.openFolderTitle
        val photoCount = state.folderItems.size
        UnhideDialog(
            itemCount = photoCount,
            originalPath = null,
            choice = unhideChoice,
            chosenFolder = chosenUnhideFolder,
            title = "Unhide \"$albumTitle\"",
            bodyText =
                "${if (photoCount == 1) "1 photo" else "$photoCount photos"} will leave the vault. " +
                    "Where should we put them?",
            originalTitle = "Original locations",
            originalSubtitle = "Each photo returns to where it came from",
            fallbackNote = "If an original folder isn't available, we'll save those photos to Downloads and tell you.",
            onChoiceChange = { unhideChoice = it },
            onPickFolder = { unhideFolderPicker.launch(null) },
            onConfirm = { destination ->
                viewModel.unhideOpenAlbum(destination)
                showOpenAlbumUnhide = false
            },
            onDismiss = { showOpenAlbumUnhide = false },
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

    // §6: bulk Move — the same Move-to sheet the viewer uses, fed the live folder tiles
    // (the "Recent" pseudo-folder maps to the category root) so create-folder appears
    // in-place the moment it lands.
    if (showMoveSheet) {
        MoveToSheet(
            itemCount = state.selectedIds.size,
            albums = state.folderTiles.map { tile -> tile.toAlbumOption() },
            currentFolderId = state.openFolderId?.takeUnless { it == CategoryState.RECENT_FOLDER_ID },
            onDismiss = { showMoveSheet = false },
            onCreateFolder = viewModel::createFolder,
            onMove = { folderId ->
                viewModel.moveSelectedToFolder(folderId)
                showMoveSheet = false
            },
        )
    }

    // §7: bulk Unhide — original-or-chosen destination with the Downloads fallback promise;
    // the batch streams under the foreground service and reports "X done, Y failed".
    if (showUnhideDialog) {
        UnhideDialog(
            itemCount = state.selectedIds.size,
            originalPath = selectionCommonPath(state),
            choice = unhideChoice,
            chosenFolder = chosenUnhideFolder,
            onChoiceChange = { unhideChoice = it },
            onPickFolder = { unhideFolderPicker.launch(null) },
            onConfirm = { destination ->
                viewModel.unhideSelected(destination)
                showUnhideDialog = false
            },
            onDismiss = { showUnhideDialog = false },
        )
    }
}

/** A folder tile as a Move-to sheet row; the "Recent" pseudo-folder is the category root. */
private fun CategoryFolderTile.toAlbumOption(): AlbumOption =
    AlbumOption(
        id = id.takeUnless { it == CategoryState.RECENT_FOLDER_ID },
        name = name,
        count = itemCount,
    )

/** Every real album name on the grid (duplicate checks for the label-editor dialogs). */
private fun albumNames(state: CategoryState): Set<String> =
    state.folderTiles
        .filterNot { it.id == CategoryState.RECENT_FOLDER_ID }
        .mapTo(mutableSetOf()) { it.name }

/**
 * §5 merge note — the album-level consequence stated before confirm: "91 photos will move
 * into Screenshots. \"Camera\" will be removed." (N>1: "…\"a\", \"b\" and 2 more will be
 * removed.").
 */
private fun albumMergeNote(
    sources: List<CategoryFolderTile>,
    targetName: String,
): String {
    val photos = sources.sumOf { it.itemCount }
    val photosText = if (photos == 1) "1 photo" else "$photos photos"
    val names = sources.map { "\"${it.name}\"" }
    val removal =
        when {
            names.size == 1 -> "${names[0]} will be removed."
            names.size == 2 -> "${names[0]} and ${names[1]} will be removed."
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} more will be removed."
        }
    return "$photosText will move into $targetName. $removal"
}

/** §7 step-1 body: album + contents semantics; an empty selection skips the scary copy. */
private fun albumDeleteChoiceMessage(
    albumCount: Int,
    photoCount: Int,
): String =
    when {
        photoCount == 0 ->
            if (albumCount == 1) "This album is empty." else "These albums are empty."
        albumCount == 1 ->
            "The album and its ${if (photoCount == 1) "1 photo" else "$photoCount photos"} move to the " +
                "Recycle Bin, recoverable for 30 days."
        else ->
            "$albumCount albums and their $photoCount photos move to the Recycle Bin, recoverable for 30 days."
    }

/** §7 step-2 body: encrypted-model wording, naming the album at N=1. */
private fun albumDeletePermanentBody(
    sources: List<CategoryFolderTile>,
    photoCount: Int,
): String =
    when {
        sources.size == 1 && photoCount == 0 ->
            "This securely erases \"${sources.single().name}\" from the vault. It cannot be recovered."
        sources.size == 1 ->
            "This securely erases \"${sources.single().name}\" and its " +
                "${if (photoCount == 1) "1 photo" else "$photoCount photos"} from the vault. " +
                "They cannot be recovered."
        else ->
            "This securely erases ${sources.size} albums and their $photoCount photos from the vault. " +
                "They cannot be recovered."
    }

/**
 * The one original path shared by every selected item, or null when the selection spans
 * folders — the bulk Unhide dialog then shows the generic "Original folder" subtitle
 * rather than implying a single destination that is only true for some of the items.
 */
private fun selectionCommonPath(state: CategoryState): String? =
    state.folderItems
        .filter { it.id in state.selectedIds }
        .map { it.relativePath }
        .distinct()
        .singleOrNull()

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
 * The category screen's header for both the album root and an open album (S10 + W3-D §7):
 * back chevron, title (+ optional count subtitle inside an album), and the trailing
 * `grid.sortButton` — the shipped ↑↓ glyph pair — opening the W3-E Sort-by sheet. The
 * sort trigger hides when the grid is empty (nothing to sort, no dead end) and never
 * renders in selection mode (the selection bars replace this header wholesale).
 */
@Composable
private fun CategoryHeader(
    title: String,
    showSort: Boolean,
    onBack: () -> Unit,
    onSortClick: () -> Unit,
    subtitle: String? = null,
    // APP-293 item 6: optional trailing ⋯ menu (the open album's "Unhide album" home).
    menu: List<SelectionOverflowItem> = emptyList(),
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(end = spacing.sm, top = spacing.sm, bottom = spacing.sm),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = colors.textPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = VaultTheme.typography.titleLarge,
                color = colors.textPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = VaultTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
            }
        }
        if (showSort) {
            // APP-293 item 11: the contemporary sort glyph (was the ↑↓ chevron pair).
            IconButton(onClick = onSortClick, modifier = Modifier.testTag("sort-button")) {
                Icon(
                    imageVector = VaultActionIcons.Sort,
                    contentDescription = "Sort",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("album-more-button")) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = colors.textPrimary,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label, color = colors.textPrimary) },
                            onClick = {
                                menuOpen = false
                                item.onClick()
                            },
                        )
                    }
                }
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
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAdd: () -> Unit,
    loadCover: suspend (String) -> ImageBitmap?,
) {
    val spacing = VaultTheme.spacing
    // APP-293 item 10: the album grid pinches too (2–4 columns — tiles carry labels).
    val pinch = rememberPinchColumnsState(initialColumns = 3, minColumns = 2, maxColumns = 4)
    LazyVerticalGrid(
        columns = GridCells.Fixed(pinch.columns),
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.lg)
                .pinchColumns(pinch)
                .testTag("album-grid"),
        contentPadding = PaddingValues(vertical = spacing.md),
    ) {
        items(tiles, key = { it.id }) { tile ->
            FolderTileCard(
                tile = tile,
                category = category,
                selected = tile.id in selectedIds,
                onClick = { onOpen(tile.id) },
                onLongPress = { onLongPress(tile.id) },
                loadCover = loadCover,
            )
        }
        // Creating/hiding mid-selection is a mode error (design §9) — the "+" tile follows
        // the FAB and hides while album selection is live.
        if (!selectionMode) {
            item(key = "__add__") {
                AddFolderTile(onClick = onAdd)
            }
        }
    }
}

/**
 * One album tile: rounded square cover (placeholder glyph when empty), name, count.
 * Selected (W2-E §9): check at the cover's top-start over an accent wash — never dimmed,
 * the name + count must stay legible while selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTileCard(
    tile: CategoryFolderTile,
    category: VaultCategory,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    loadCover: suspend (String) -> ImageBitmap?,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val cover: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, tile.cover?.id) {
        value = tile.cover?.let { loadCover(it.id) }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .padding(spacing.xs)
                .testTag("album-tile-${tile.id}")
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
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
            if (selected) {
                Box(modifier = Modifier.fillMaxSize().background(colors.accent.copy(alpha = 0.14f)))
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = colors.accent,
                    modifier = Modifier.align(Alignment.TopStart).padding(spacing.xs).size(20.dp),
                )
            }
            // album.pinBadge (W3-D §3/§4): top-END so it never collides with the
            // top-start selection check; the badge is the pin state's only chrome.
            if (tile.pinned) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(spacing.xs)
                            .size(VaultGridTokens.PinBadgeSize)
                            .clip(CircleShape)
                            .background(VaultGridTokens.PinBadgeContainer)
                            .testTag("pin-badge-${tile.id}"),
                ) {
                    Icon(
                        imageVector = VaultGridTokens.PushPin,
                        contentDescription = "Pinned",
                        tint = VaultGridTokens.PinBadgeGlyph,
                        modifier = Modifier.size(VaultGridTokens.PinBadgeGlyphSize),
                    )
                }
            }
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
 * speech-bubble card anchored above it, with two glyph rows — "Create album" and the
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
                        // "Album" terminology lock (W2-E §1, APP-218 fold-in).
                        label = "Create album",
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
