package com.appblish.calculatorvault.vault

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.actions.UnhideMessages
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.model.GridSort
import com.appblish.calculatorvault.vault.model.SortDirection
import com.appblish.calculatorvault.vault.model.SortKey
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.model.sortItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One tile on the category root's folder grid (S10): a real [VaultFolder] or the synthetic
 * "Recent" pseudo-folder that surfaces items with no folder ([VaultItem.folderId] == null),
 * so legacy/root items are never hidden from the user. [cover] is the newest contained item
 * (backs the tile thumbnail; null → placeholder glyph); [newestSortKey] drives date sorting.
 */
data class CategoryFolderTile(
    val id: String,
    val name: String,
    val itemCount: Int,
    val cover: VaultItem? = null,
    val newestSortKey: Long = 0L,
    // W3-E album-sort inputs, all index-derived (spec §4.1: zero decryption to sort) —
    // Size = contents sum, Last modified = the later of the label change and the newest
    // member's added-to-vault time — plus the pin bit for the two-cluster ordering (§3.6).
    val sizeBytes: Long = 0L,
    val lastModifiedMs: Long = 0L,
    val pinned: Boolean = false,
)

/**
 * Order the album grid's tiles per the W3-D §4/§7 pin × sort contract: **pinned cluster
 * above unpinned, the active key + direction applied independently within each cluster,
 * pin state never a sort key** (design G-1). Pure and top-level so CI/JVM tests can
 * assert exact orders. Tiebreak: name (case-insensitive) ascending, then id.
 */
fun orderAlbumTiles(
    tiles: List<CategoryFolderTile>,
    sort: GridSort,
): List<CategoryFolderTile> {
    val (pinned, unpinned) = tiles.partition { it.pinned }
    return sortAlbumTiles(pinned, sort) + sortAlbumTiles(unpinned, sort)
}

private fun sortAlbumTiles(
    tiles: List<CategoryFolderTile>,
    sort: GridSort,
): List<CategoryFolderTile> {
    val byName =
        compareBy(String.CASE_INSENSITIVE_ORDER) { tile: CategoryFolderTile -> tile.name }.thenBy { it.id }
    val comparator =
        when (sort.key) {
            SortKey.NAME -> if (sort.direction == SortDirection.DESCENDING) byName.reversed() else byName
            // DATE_TAKEN never reaches an album sheet (design G-7); treat it as the
            // album default if a corrupt index ever carries it.
            SortKey.DATE_TAKEN -> byName
            else -> {
                val selector: (CategoryFolderTile) -> Long =
                    if (sort.key == SortKey.SIZE) ({ it.sizeBytes }) else ({ it.lastModifiedMs })
                val primary = compareBy(selector)
                (if (sort.direction == SortDirection.DESCENDING) primary.reversed() else primary).then(byName)
            }
        }
    return tiles.sortedWith(comparator)
}

/**
 * A category screen's state, folder-grid-first (S10/S17): the root shows [folderTiles]
 * only; tapping one sets [openFolderId] and the screen shows that folder's items
 * ([folderItems]) in the date-grouped grid with multi-select. Root ↔ folder navigation is
 * internal ViewModel state (no nav route), so back inside a folder returns to the grid.
 */
data class CategoryState(
    val category: VaultCategory,
    val items: List<VaultItem> = emptyList(),
    val folderTiles: List<CategoryFolderTile> = emptyList(),
    val openFolderId: String? = null,
    // W3-E persisted sorts: the album grid's active sort, the open photo grid's
    // *effective* sort (per-album override when present, else the vault-wide choice),
    // and whether the open album carries an override (the "This album only" checkbox).
    val albumSort: GridSort = GridSort.ALBUM_DEFAULT,
    val photoSort: GridSort = GridSort.PHOTO_DEFAULT,
    val photoSortOverridden: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    // Album multi-select on the root grid (W2-E §9): long-press an album tile to enter,
    // tap-toggle, Select All — deliberately no drag-range (album grids are small).
    val selectedAlbumIds: Set<String> = emptySet(),
    val albumSelectionMode: Boolean = false,
    // The raw album records behind the tiles — the Album property dialog reads its
    // Created/Modified stamps from these index records (spec §1.3, design §8).
    val albums: List<VaultFolder> = emptyList(),
    // The pending one-per-operation summary notice (D-3, generalized for P2-3): restore /
    // recycle-bin / permanent-delete outcomes plus the hide picker's "N hidden" hand-off.
    // The screen shows it as a snackbar and consumes it. Null when nothing is pending.
    val opNotice: String? = null,
) {
    val inFolder: Boolean get() = openFolderId != null

    /** The selected album tiles, in display order (drives dialog copy + property rows). */
    val selectedAlbumTiles: List<CategoryFolderTile> get() = folderTiles.filter { it.id in selectedAlbumIds }

    /** Total photos across the selected albums ("91 photos will leave the vault…"). */
    val selectedAlbumItemCount: Int get() = selectedAlbumTiles.sumOf { it.itemCount }

    /**
     * Items of the open folder in **display order** — the effective photo sort applied
     * (W3-E §7); the "Recent" pseudo-folder maps to folder-less items. The grid, the
     * drag-select range math, and the pager viewer all read this one ordering.
     */
    val folderItems: List<VaultItem> get() =
        when (openFolderId) {
            null -> emptyList()
            RECENT_FOLDER_ID -> sortItems(items.filter { it.folderId == null }, photoSort)
            else -> sortItems(items.filter { it.folderId == openFolderId }, photoSort)
        }

    /** Title for the open folder's top bar; survives the tile vanishing mid-visit. */
    val openFolderTitle: String get() =
        folderTiles.firstOrNull { it.id == openFolderId }?.name
            ?: if (openFolderId == RECENT_FOLDER_ID) RECENT_FOLDER_NAME else ""

    companion object {
        /**
         * Id/name of the synthetic "Recent" pseudo-folder listing items with no folderId
         * (legacy/root items). Mirrors the hide picker's "Recent" aggregate so the two
         * folder grids read the same; it only appears while such items exist.
         */
        const val RECENT_FOLDER_ID = "__recent__"
        const val RECENT_FOLDER_NAME = "Recent"
    }
}

/**
 * Backs one category screen (Photos/Videos/Audios/Files/Contacts) with the folder-first
 * state machine (APP-225): root folder grid → open folder → back. Owns the transient
 * selection/navigation/sort state locally and delegates every mutation — recycle-bin,
 * permanent delete, restore, folder move, create folder — to the shared
 * [VaultContentRepository], so other screens see the change immediately.
 */
class CategoryViewModel(
    val category: VaultCategory,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    /**
     * Screen-local state: selection, the in-flight drag-select gesture (anchor + the
     * selection snapshot the drag started from), which folder is open, and the grid sort.
     */
    private data class LocalState(
        val ids: Set<String> = emptySet(),
        val active: Boolean = false,
        val dragAnchorId: String? = null,
        val dragBase: Set<String> = emptySet(),
        val openFolderId: String? = null,
        // Album selection on the root grid (W2-E §9), independent of the item selection
        // inside an open folder — the two modes can never be live at once (different views).
        val albumIds: Set<String> = emptySet(),
        val albumActive: Boolean = false,
    )

    // One notice per mutation (D-3, generalized for P2-3), merged from the two shared
    // process-wide slots: bulk ops (move/unhide/recycle/delete — app-scoped so they
    // survive this ViewModel, W1-E3) and the hide picker's "N hidden" hand-off. Bulk
    // summaries win if both are pending; the consume handshake prevents re-shows.
    private val notices =
        combine(BulkOps.summary, HideImportViewModel.hideSummary) { bulk, hide -> bulk ?: hide }

    val state: StateFlow<CategoryState> =
        combine(
            repository.items(category),
            repository.folders(category),
            repository.sortPrefs(),
            local,
            notices,
        ) { items, folders, prefs, loc, notice ->
            // Drop selections that no longer exist (e.g. after a recycle-bin move).
            val validIds = loc.ids.filter { id -> items.any { it.id == id } }.toSet()
            val validAlbumIds = loc.albumIds.filter { id -> folders.any { it.id == id } }.toSet()
            val openFolder = folders.firstOrNull { it.id == loc.openFolderId }
            CategoryState(
                category = category,
                items = items,
                folderTiles = folderTiles(items, folders, prefs.albumSort),
                openFolderId = loc.openFolderId,
                albumSort = prefs.albumSort,
                photoSort = openFolder?.photoSortOverride ?: prefs.photoSort,
                photoSortOverridden = openFolder?.photoSortOverride != null,
                selectedIds = validIds,
                selectionMode = loc.active && validIds.isNotEmpty(),
                selectedAlbumIds = validAlbumIds,
                albumSelectionMode = loc.albumActive && validAlbumIds.isNotEmpty(),
                albums = folders,
                opNotice = notice,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CategoryState(category),
        )

    /** Open [folderId]'s contents (S17). Clears any selection from a previous folder. */
    fun openFolder(folderId: String) {
        local.update {
            it.copy(
                openFolderId = folderId,
                ids = emptySet(),
                active = false,
                albumIds = emptySet(),
                albumActive = false
            )
        }
    }

    /** Back from a folder's contents to the root folder grid. */
    fun closeFolder() {
        local.update { it.copy(openFolderId = null, ids = emptySet(), active = false) }
    }

    // --- W3-E sort (§7): persisted in the encrypted index, applied live from the sheet ---

    /** Persist the album-grid sort (vault-wide, one setting per grid type). */
    fun setAlbumSort(sort: GridSort) {
        viewModelScope.launch { repository.setAlbumSort(sort) }
    }

    /**
     * Apply a photo-grid sort from the sheet: while the open album carries a "This album
     * only" override the choice lands on that override; otherwise it is the vault-wide
     * photo sort. Either way it persists in the encrypted index.
     */
    fun setPhotoSort(sort: GridSort) {
        val overriddenFolder = realOpenFolderId()?.takeIf { state.value.photoSortOverridden }
        viewModelScope.launch {
            if (overriddenFolder != null) {
                repository.setAlbumPhotoSortOverride(overriddenFolder, sort)
            } else {
                repository.setPhotoSort(sort)
            }
        }
    }

    /**
     * Toggle the "This album only" checkbox (design G-8): on stamps the current effective
     * sort as this album's override; off clears it, reverting to the vault-wide setting.
     */
    fun setPhotoSortOverride(enabled: Boolean) {
        val folderId = realOpenFolderId() ?: return
        val current = state.value.photoSort
        viewModelScope.launch {
            repository.setAlbumPhotoSortOverride(folderId, if (enabled) current else null)
        }
    }

    /** The open folder's id when it is a real album (never the "Recent" pseudo-folder). */
    private fun realOpenFolderId(): String? =
        local.value.openFolderId?.takeUnless { it == CategoryState.RECENT_FOLDER_ID }

    // --- W3-E pin + cover (§4–§6): single index writes, the tile is the confirmation ---

    /**
     * Pin/unpin the single selected album (§4): selection mode exits and the tile
     * animates to its cluster — no snackbar ("object is the interface").
     */
    fun togglePinSelectedAlbum() {
        val tile = state.value.selectedAlbumTiles.singleOrNull() ?: return
        clearAlbumSelection()
        viewModelScope.launch { repository.setFolderPinned(tile.id, !tile.pinned) }
    }

    /**
     * Commit the Choose-cover picker's selection (§6): an index pointer write; the picker
     * closes back to the grid where the updated tile is visible — no snackbar.
     */
    fun setAlbumCover(
        folderId: String,
        itemId: String,
    ) {
        clearAlbumSelection()
        viewModelScope.launch { repository.setFolderCover(folderId, itemId) }
    }

    /**
     * "Set as cover" from photo-selection mode at N=1 (§5): the album is the open one
     * (implicit); surfaces the required snackbar — the changed home tile is off-screen.
     */
    fun setCoverFromSelection() {
        val folderId = realOpenFolderId() ?: return
        val itemId = local.value.ids.singleOrNull() ?: return
        clearSelection()
        BulkOps.run {
            repository.setFolderCover(folderId, itemId)
            "Set as album cover."
        }
    }

    /**
     * Long-press enters selection mode with the pressed item selected; while already
     * selecting it *adds* the pressed item (the drag-select anchor gesture) rather than
     * resetting the set — a long press must never destroy an existing selection (W1-E3).
     */
    fun startSelection(itemId: String) {
        local.update { it.copy(ids = it.ids + itemId, active = true) }
    }

    /**
     * Tap while selecting toggles membership; leaving the set empty exits the mode. Taps
     * are ignored while a drag session is live: the up that *ends* a long-press gesture
     * also fires the pressed tile's click, and without the guard it would instantly
     * un-select the item the long-press just anchored.
     */
    fun toggle(itemId: String) {
        local.update { current ->
            if (!current.active || current.dragAnchorId != null) return@update current
            val ids = if (itemId in current.ids) current.ids - itemId else current.ids + itemId
            current.copy(ids = ids, active = ids.isNotEmpty())
        }
    }

    /**
     * Route a tile tap from its always-fresh local state (never the composition's state
     * snapshot, which can lag a just-fired long-press): during a live drag session the
     * tap is gesture noise (see [toggle]); in selection mode it toggles; otherwise it
     * returns the item for the screen to open in the viewer.
     */
    fun tappedItem(itemId: String): VaultItem? {
        val current = local.value
        if (current.dragAnchorId != null) return null
        if (current.active && current.ids.isNotEmpty()) {
            toggle(itemId)
            return null
        }
        return state.value.folderItems.firstOrNull { it.id == itemId }
    }

    /**
     * Select All over the open folder's items (W1-E3): selects every item, or — when the
     * folder is already fully selected — clears the selection, so the one control toggles.
     */
    fun selectAllInFolder() {
        val all = state.value.folderItems
            .map { it.id }
            .toSet()
        if (all.isEmpty()) return
        local.update {
            if (it.ids.containsAll(all)) {
                it.copy(ids = emptySet(), active = false)
            } else {
                it.copy(ids = all, active = true)
            }
        }
    }

    /**
     * A long-press-drag began on [itemId] (W1-E3): make it the range anchor and snapshot
     * the selection the gesture starts from, so dragging backwards only ever releases
     * items this gesture itself swept up. With drag-select wired the grid detector owns
     * long-press outright (tiles drop their own handler), so this is also what *enters*
     * selection mode on a plain long-press — a no-movement press is just an empty drag.
     */
    fun beginDragSelect(itemId: String) {
        local.update {
            val ids = it.ids + itemId
            it.copy(ids = ids, active = true, dragAnchorId = itemId, dragBase = ids)
        }
    }

    /** The drag-select pointer is over [itemId]: selection = base + anchor..target range. */
    fun dragSelectOver(itemId: String) {
        val ordered = displayOrderedIds()
        local.update { current ->
            val anchor = current.dragAnchorId ?: return@update current
            current.copy(ids = DragSelection.rangeSelect(ordered, current.dragBase, anchor, itemId))
        }
    }

    /** The drag-select gesture ended; the accumulated selection stays. */
    fun endDragSelect() {
        local.update { it.copy(dragAnchorId = null, dragBase = emptySet()) }
    }

    fun clearSelection() {
        local.update { it.copy(ids = emptySet(), active = false, dragAnchorId = null, dragBase = emptySet()) }
    }

    // --- Album selection on the root grid (W2-E §9) --------------------------------

    /**
     * Long-press an album tile enters album selection mode with that album selected
     * (design §9). The synthetic "Recent" pseudo-folder is not an album — it has no label
     * in the index to rename/move/delete — so it never participates in selection.
     */
    fun startAlbumSelection(folderId: String) {
        if (folderId == CategoryState.RECENT_FOLDER_ID) return
        local.update { it.copy(albumIds = it.albumIds + folderId, albumActive = true) }
    }

    /** Tap while album-selecting toggles membership; deselect-to-zero exits the mode. */
    fun toggleAlbum(folderId: String) {
        if (folderId == CategoryState.RECENT_FOLDER_ID) return
        local.update { current ->
            if (!current.albumActive) return@update current
            val ids = if (folderId in current.albumIds) current.albumIds - folderId else current.albumIds + folderId
            current.copy(albumIds = ids, albumActive = ids.isNotEmpty())
        }
    }

    /**
     * Route an album-tile tap: toggling while selection mode is live, opening otherwise.
     * Returns true when the tap was consumed as a selection toggle.
     */
    fun tappedAlbum(folderId: String): Boolean {
        val current = local.value
        if (current.albumActive && current.albumIds.isNotEmpty()) {
            toggleAlbum(folderId)
            return true
        }
        return false
    }

    /**
     * Select All over the root grid's real albums (never the "Recent" pseudo-folder);
     * when everything is already selected the one control clears instead — the same
     * toggle contract as the item-level Select All (W1-E3).
     */
    fun selectAllAlbums() {
        val all =
            state.value.folderTiles
                .map { it.id }
                .filterNot { it == CategoryState.RECENT_FOLDER_ID }
                .toSet()
        if (all.isEmpty()) return
        local.update {
            if (it.albumIds.containsAll(all)) {
                it.copy(albumIds = emptySet(), albumActive = false)
            } else {
                it.copy(albumIds = all, albumActive = true)
            }
        }
    }

    fun clearAlbumSelection() {
        local.update { it.copy(albumIds = emptySet(), albumActive = false) }
    }

    /**
     * The open folder's item ids in *display* order — [CategoryState.folderItems] already
     * applies the effective photo sort (W3-E §7) the grid renders — so a drag range
     * matches what the user sees, not the raw repository order.
     */
    private fun displayOrderedIds(): List<String> = state.value.folderItems.map { it.id }

    /**
     * Load a grid thumbnail for [itemId] through [VaultThumbnailPipeline] (APP-244):
     * memory LRU → encrypted stored thumb → one-time backfill from the blob. Instant on
     * revisits, never decrypts a full photo/video for a tile, always off the main thread.
     * Also backs the folder tiles' cover thumbnails (a cover is just the newest item).
     */
    suspend fun thumbnail(
        context: Context,
        itemId: String,
    ): ImageBitmap? {
        val item = state.value.items.firstOrNull { it.id == itemId } ?: return null
        return VaultThumbnailPipeline.load(context, item, repository)
    }

    /**
     * "Move to Recycle Bin" from the delete-choice dialog (D-4): recoverable for 30 days.
     * Surfaces a one-per-operation summary ("3 items moved to Recycle Bin") via
     * [CategoryState.opNotice] — same feedback contract as unhide (P2-3).
     */
    fun recycleSelected() =
        mutateSelection { ids ->
            repository.moveToRecycleBin(ids)
            "${countLabel(ids.size)} moved to Recycle Bin"
        }

    /**
     * "Delete permanently" from the delete-choice dialog (D-4): the direct spec §1.5 path —
     * secure blob wipe + index removal under the bulk foreground service, never routed
     * through the bin. The summary counts what was actually destroyed, calling out any
     * miss ("2 deleted, 1 failed") — a delete must never over-report (W1-E3 DoD).
     */
    fun deleteSelectedForever() =
        mutateSelection { ids ->
            val deleted = repository.permanentlyDelete(ids)
            val failed = ids.size - deleted
            if (failed > 0) "${countLabel(deleted)} deleted, $failed failed" else "${countLabel(deleted)} deleted"
        }

    /**
     * Bulk unhide to [destination] (§7): decrypt each blob back to public storage —
     * streaming, under the foreground service — and surface the honest per-destination
     * summary ("Unhid 2 · saved 1 to Downloads (original unavailable).") via
     * [CategoryState.opNotice] — never silent (design call D-3 / spec §1.4).
     */
    fun unhideSelected(destination: UnhideDestination) =
        mutateSelection { ids -> UnhideMessages.summary(repository.unhideTo(ids, destination)) }

    /**
     * The screen calls this once the summary snackbar has been shown (or dismissed).
     * Clears the shared bulk-op summary *and* the hide picker's slot — one snackbar
     * consumes whichever source produced it.
     */
    fun consumeOpNotice() {
        BulkOps.consume()
        HideImportViewModel.consumeHideSummary()
    }

    /** Bulk move into a folder (§6, index-only): summarized as "Moved 3 items to Trip". */
    fun moveSelectedToFolder(folderId: String?) {
        val destination = folderLabel(folderId)
        mutateSelection { ids ->
            repository.moveToFolder(ids, folderId)
            "Moved ${countLabel(ids.size)} to $destination"
        }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createFolder(category, trimmed) }
    }

    // --- Album-level actions (W2-E §4–§8) -------------------------------------------

    /**
     * Rename an album (§4): a single, instant, index-label-only write — no progress UI and
     * deliberately no snackbar (the renamed tile *is* the confirmation, per the "object is
     * the interface" rule). Duplicate/empty guards live in the dialog; unchanged names are
     * dismissed by the screen as a no-op before reaching here.
     */
    fun renameAlbum(
        folderId: String,
        name: String,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameFolder(folderId, trimmed) }
    }

    /**
     * Merge the selected albums' contents into [targetFolderId] (§5): index-entry
     * relocation only, sources removed once emptied. Summary is album-aware for N>1
     * ("2 albums · 141 photos moved") per design §10.
     */
    fun moveSelectedAlbums(targetFolderId: String) {
        val destination = folderLabel(targetFolderId)
        mutateAlbumSelection { ids ->
            val albumCount = ids.size
            val moved = repository.mergeFolders(ids, targetFolderId)
            if (albumCount > 1) {
                "$albumCount albums · ${photosLabel(moved)} moved to $destination"
            } else {
                "${photosLabel(moved)} moved to $destination"
            }
        }
    }

    /**
     * Whole-album unhide (§6): every photo decrypts out under the photo-level fallback
     * rules; a fully-emptied album's label is removed, a partial failure keeps the album
     * holding exactly the failed photos. Summary via the shared honest builder.
     */
    fun unhideSelectedAlbums(destination: UnhideDestination) =
        mutateAlbumSelection { ids -> UnhideMessages.summary(repository.unhideFolders(ids, destination)) }

    /**
     * Album delete → Recycle Bin (§7 soft path): contents to the bin still encrypted,
     * keeping their album grouping (F-3); the label becomes a bin tombstone. An empty
     * album just loses its label — no service run, honest summary either way.
     */
    fun recycleSelectedAlbums() =
        mutateAlbumSelection { ids ->
            val albumCount = ids.size
            val binned = repository.moveFoldersToRecycleBin(ids)
            when {
                binned == 0 -> if (albumCount == 1) "Album removed" else "$albumCount albums removed"
                albumCount > 1 -> "$albumCount albums · ${photosLabel(binned)} moved to Recycle Bin"
                else -> "Album · ${photosLabel(binned)} moved to Recycle Bin"
            }
        }

    /**
     * Album delete → Permanent (§7 hard path, behind the 2-step confirm): secure wipe of
     * every blob + index entries + labels. The summary counts what was actually destroyed
     * and calls out any miss — never over-reports (W1-E3 DoD).
     */
    fun deleteSelectedAlbumsForever() {
        val expected = state.value.selectedAlbumItemCount
        mutateAlbumSelection { ids ->
            val albumCount = ids.size
            val erased = repository.permanentlyDeleteFolders(ids)
            val failed = (expected - erased).coerceAtLeast(0)
            val albums = if (albumCount == 1) "1 album" else "$albumCount albums"
            when {
                expected == 0 -> if (albumCount == 1) "Album deleted" else "$albumCount albums deleted"
                failed > 0 -> "$albums · $erased erased, $failed failed"
                else -> "$albums · $erased erased"
            }
        }
    }

    /**
     * Run a bulk album mutation through [BulkOps] — app-scoped like [mutateSelection], so
     * navigating away never cancels a half-done batch and the summary lands on the shared
     * notice slot (design §10: a summary appears for every album bulk op).
     */
    private fun mutateAlbumSelection(block: suspend (Set<String>) -> String?) {
        val ids = local.value.albumIds
        if (ids.isEmpty()) return
        clearAlbumSelection()
        BulkOps.run { block(ids) }
    }

    /** Display name of a move destination; null folderId = the category root ("Recent"). */
    private fun folderLabel(folderId: String?): String =
        when (folderId) {
            null -> CategoryState.RECENT_FOLDER_NAME
            else ->
                state.value.folderTiles
                    .firstOrNull { it.id == folderId }
                    ?.name ?: "folder"
        }

    /**
     * Run a bulk mutation over the current selection through [BulkOps] — app-scoped, so
     * navigating away (which clears this ViewModel) can never cancel a half-done batch
     * (W1-E3). Selection mode exits immediately; the op's summary arrives on the shared
     * notice slot whenever it completes, even if the user has moved on and come back.
     */
    private fun mutateSelection(block: suspend (Set<String>) -> String?) {
        val ids = local.value.ids
        if (ids.isEmpty()) return
        clearSelection()
        BulkOps.run { block(ids) }
    }

    private companion object {
        /** Pluralized count for the P2-3 operation summaries ("1 item" / "3 items"). */
        fun countLabel(count: Int): String = if (count == 1) "1 item" else "$count items"

        /** Pluralized photo count for the album-aware W2-E summaries ("1 photo" / "91 photos"). */
        fun photosLabel(count: Int): String = if (count == 1) "1 photo" else "$count photos"

        /**
         * Build the root grid's tiles: every real folder (count/cover derived from the
         * live items, not the folder's stored count) plus, when folder-less items exist,
         * the "Recent" pseudo-folder first — the simplest presentation that never hides
         * an item from the user. Real albums follow the W3-E two-cluster ordering
         * ([orderAlbumTiles]): pinned above unpinned, [sort] within each cluster. The
         * cover is the album's explicit cover pointer when it resolves, else the newest
         * member by added-to-vault time (design G-5 fallback).
         */
        fun folderTiles(
            items: List<VaultItem>,
            folders: List<VaultFolder>,
            sort: GridSort,
        ): List<CategoryFolderTile> {
            val byFolder = items.groupBy { it.folderId }
            val real =
                folders.map { folder ->
                    val contents = byFolder[folder.id].orEmpty()
                    val newest = contents.maxOfOrNull { it.sortKey } ?: 0L
                    CategoryFolderTile(
                        id = folder.id,
                        name = folder.name,
                        itemCount = contents.size,
                        cover =
                            contents.firstOrNull { it.id == folder.coverItemId }
                                ?: contents.maxByOrNull { it.sortKey },
                        newestSortKey = newest,
                        sizeBytes = contents.sumOf { it.sizeBytes },
                        lastModifiedMs = maxOf(folder.modifiedAt, newest),
                        pinned = folder.pinned,
                    )
                }
            val rootItems = byFolder[null].orEmpty()
            val recent =
                if (rootItems.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        CategoryFolderTile(
                            id = CategoryState.RECENT_FOLDER_ID,
                            name = CategoryState.RECENT_FOLDER_NAME,
                            itemCount = rootItems.size,
                            cover = rootItems.maxByOrNull { it.sortKey },
                            newestSortKey = rootItems.maxOfOrNull { it.sortKey } ?: 0L,
                        ),
                    )
                }
            return recent + orderAlbumTiles(real, sort)
        }
    }
}
