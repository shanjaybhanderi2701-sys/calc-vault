package com.appblish.calculatorvault.vault

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.components.groupMediaByDate
import com.appblish.calculatorvault.vault.actions.UnhideMessages
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
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
)

/**
 * Sort orders for the category root's folder grid (S16) — folders sort by name or date,
 * each direction. Kept as a small local enum (the picker's [PickerSort] sorts *items* by
 * added-time/size and doesn't fit folders). [sorted] is pure so ordering is unit-testable.
 */
enum class FolderSort(
    val label: String,
) {
    DATE_DESC("Date · newest first"),
    DATE_ASC("Date · oldest first"),
    NAME_ASC("Name · A to Z"),
    NAME_DESC("Name · Z to A"),
    ;

    /** Order [tiles] for the grid; empty folders (no newest item) sink on date sorts. */
    fun sorted(tiles: List<CategoryFolderTile>): List<CategoryFolderTile> =
        when (this) {
            DATE_DESC -> tiles.sortedByDescending { it.newestSortKey }
            DATE_ASC -> tiles.sortedBy { it.newestSortKey }
            NAME_ASC -> tiles.sortedBy { it.name.lowercase() }
            NAME_DESC -> tiles.sortedByDescending { it.name.lowercase() }
        }
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
    val folderSort: FolderSort = FolderSort.DATE_DESC,
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    // The pending one-per-operation summary notice (D-3, generalized for P2-3): restore /
    // recycle-bin / permanent-delete outcomes plus the hide picker's "N hidden" hand-off.
    // The screen shows it as a snackbar and consumes it. Null when nothing is pending.
    val opNotice: String? = null,
) {
    val inFolder: Boolean get() = openFolderId != null

    /** Items of the open folder; the "Recent" pseudo-folder maps to folder-less items. */
    val folderItems: List<VaultItem> get() =
        when (openFolderId) {
            null -> emptyList()
            RECENT_FOLDER_ID -> items.filter { it.folderId == null }
            else -> items.filter { it.folderId == openFolderId }
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
        val sort: FolderSort = FolderSort.DATE_DESC,
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
            local,
            notices,
        ) { items, folders, loc, notice ->
            // Drop selections that no longer exist (e.g. after a recycle-bin move).
            val validIds = loc.ids.filter { id -> items.any { it.id == id } }.toSet()
            CategoryState(
                category = category,
                items = items,
                folderTiles = folderTiles(items, folders, loc.sort),
                openFolderId = loc.openFolderId,
                folderSort = loc.sort,
                selectedIds = validIds,
                selectionMode = loc.active && validIds.isNotEmpty(),
                opNotice = notice,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CategoryState(category),
        )

    /** Open [folderId]'s contents (S17). Clears any selection from a previous folder. */
    fun openFolder(folderId: String) {
        local.update { it.copy(openFolderId = folderId, ids = emptySet(), active = false) }
    }

    /** Back from a folder's contents to the root folder grid. */
    fun closeFolder() {
        local.update { it.copy(openFolderId = null, ids = emptySet(), active = false) }
    }

    /** Change the folder-grid sort (S16 ↑↓ control). */
    fun setFolderSort(sort: FolderSort) {
        local.update { it.copy(sort = sort) }
    }

    /**
     * Long-press enters selection mode with the pressed item selected; while already
     * selecting it *adds* the pressed item (the drag-select anchor gesture) rather than
     * resetting the set — a long press must never destroy an existing selection (W1-E3).
     */
    fun startSelection(itemId: String) {
        local.update { it.copy(ids = it.ids + itemId, active = true) }
    }

    /** Tap while selecting toggles membership; leaving the set empty exits the mode. */
    fun toggle(itemId: String) {
        local.update { current ->
            if (!current.active) return@update current
            val ids = if (itemId in current.ids) current.ids - itemId else current.ids + itemId
            current.copy(ids = ids, active = ids.isNotEmpty())
        }
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
     * items this gesture itself swept up. Idempotent with [startSelection], which the
     * tile's own long-press handler fires for the same touch.
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

    /**
     * The open folder's item ids in *display* order — the same date-section grouping the
     * grid renders ([groupMediaByDate]) — so a drag range matches what the user sees, not
     * the raw repository order.
     */
    private fun displayOrderedIds(): List<String> =
        groupMediaByDate(state.value.folderItems.map { MediaItem(it.id, it.dateLabel, it.sortKey) })
            .flatMap { group -> group.items }
            .map { it.id }

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

        /**
         * Build the root grid's tiles: every real folder (count/cover derived from the
         * live items, not the folder's stored count) plus, when folder-less items exist,
         * the "Recent" pseudo-folder pinned first — the simplest presentation that never
         * hides an item from the user. Real folders are ordered by [sort].
         */
        fun folderTiles(
            items: List<VaultItem>,
            folders: List<VaultFolder>,
            sort: FolderSort,
        ): List<CategoryFolderTile> {
            val byFolder = items.groupBy { it.folderId }
            val real =
                folders.map { folder ->
                    val contents = byFolder[folder.id].orEmpty()
                    CategoryFolderTile(
                        id = folder.id,
                        name = folder.name,
                        itemCount = contents.size,
                        cover = contents.maxByOrNull { it.sortKey },
                        newestSortKey = contents.maxOfOrNull { it.sortKey } ?: 0L,
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
            return recent + sort.sorted(real)
        }
    }
}
