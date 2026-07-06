package com.appblish.calculatorvault.vault

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.media.VaultThumbnails
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

    // One notice per mutation (D-3, generalized for P2-3): set by restore/recycle/delete,
    // consumed by the screen once its snackbar has shown. A plain StateFlow (not an event
    // bus) keeps the combine below simple; the consume handshake prevents re-shows.
    private val opNotice = MutableStateFlow<String?>(null)

    /** Screen-local state: selection, which folder is open, and the folder-grid sort. */
    private data class LocalState(
        val ids: Set<String> = emptySet(),
        val active: Boolean = false,
        val openFolderId: String? = null,
        val sort: FolderSort = FolderSort.DATE_DESC,
    )

    val state: StateFlow<CategoryState> =
        combine(
            repository.items(category),
            repository.folders(category),
            local,
            opNotice,
            // The hide picker pops back the moment its op completes, so its "N hidden"
            // summary (P2-3) travels through the shared slot and surfaces here — on the
            // category screen the user lands back on. Own notices win if both are pending.
            HideImportViewModel.hideSummary,
        ) { items, folders, loc, notice, hideSummary ->
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
                opNotice = notice ?: hideSummary,
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

    /** Long-press starts selection mode with the pressed item selected. */
    fun startSelection(itemId: String) {
        local.update { it.copy(ids = setOf(itemId), active = true) }
    }

    /** Tap while selecting toggles membership; leaving the set empty exits the mode. */
    fun toggle(itemId: String) {
        local.update { current ->
            if (!current.active) return@update current
            val ids = if (itemId in current.ids) current.ids - itemId else current.ids + itemId
            current.copy(ids = ids, active = ids.isNotEmpty())
        }
    }

    fun clearSelection() {
        local.update { it.copy(ids = emptySet(), active = false) }
    }

    /**
     * Decode a grid thumbnail for [itemId] from its decrypted blob (image bitmap / video
     * frame; null for non-visual categories). Runs off the caller's composition on IO.
     * Also backs the folder tiles' cover thumbnails (a cover is just the newest item).
     */
    suspend fun thumbnail(
        context: Context,
        itemId: String,
    ): ImageBitmap? {
        val item = state.value.items.firstOrNull { it.id == itemId } ?: return null
        return VaultThumbnails.forItem(context, item) { repository.openDecrypted(itemId) }
    }

    /**
     * "Move to Recycle Bin" from the delete-choice dialog (D-4): recoverable for 30 days.
     * Surfaces a one-per-operation summary ("3 items moved to Recycle Bin") via
     * [CategoryState.opNotice] — same feedback contract as restore (P2-3).
     */
    fun recycleSelected() =
        mutateSelection { ids ->
            repository.moveToRecycleBin(ids)
            opNotice.value = "${countLabel(ids.size)} moved to Recycle Bin"
        }

    /**
     * "Delete permanently" from the delete-choice dialog (D-4). The repository contract
     * scopes [VaultContentRepository.deleteForever] to recycle-bin entries, so this routes
     * the selection *through* the bin and destroys it in one gesture — same end state as a
     * direct permanent delete, without widening the repository interface. Surfaces the
     * "2 items deleted" summary via [CategoryState.opNotice] (P2-3).
     */
    fun deleteSelectedForever() =
        mutateSelection { ids ->
            repository.moveToRecycleBin(ids)
            repository.deleteForever(ids)
            opNotice.value = "${countLabel(ids.size)} deleted"
        }

    /**
     * Restore the selection: decrypt back to public storage so it returns to the gallery
     * (spec §8 vocabulary — the action is "Restore", never "unhide"). The per-operation
     * [com.appblish.calculatorvault.vault.model.RestoreSummary] outcome is surfaced as ONE
     * snackbar notice via [CategoryState.opNotice] — never silent (design call D-3).
     */
    fun restoreSelected() =
        mutateSelection { ids ->
            val summary = repository.unhideDetailed(ids)
            opNotice.value = summary.noticeText()
        }

    /**
     * The screen calls this once the summary snackbar has been shown (or dismissed).
     * Clears the own restore/recycle/delete notice *and* the shared hide summary — one
     * snackbar consumes whichever source produced it.
     */
    fun consumeOpNotice() {
        opNotice.value = null
        HideImportViewModel.consumeHideSummary()
    }

    fun moveSelectedToFolder(folderId: String?) = mutateSelection { repository.moveToFolder(it, folderId) }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createFolder(category, trimmed) }
    }

    private fun mutateSelection(block: suspend (Set<String>) -> Unit) {
        val ids = local.value.ids
        if (ids.isEmpty()) return
        viewModelScope.launch {
            block(ids)
            clearSelection()
        }
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
