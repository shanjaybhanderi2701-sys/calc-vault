package com.appblish.calculatorvault.vault

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Recycle-bin state: surviving entries, the current multi-select, and the pending
 * one-per-operation summary notice (D-3 / spec §1.6) the screen shows as a snackbar.
 */
data class RecycleBinState(
    val entries: List<RecycleBinEntry> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val opNotice: String? = null,
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * Backs the recycle-bin screen (W1-E4). Purges anything past the auto-delete window on
 * open, then exposes the surviving entries with per-item "N days left". Restore returns
 * items to their album (index entry + blob, both still encrypted); delete-forever securely
 * wipes the blob (guard behind a destructive confirm in the UI). Both bulk ops run through
 * [BulkOps] — app-scoped so a batch survives this ViewModel being cleared, off the main
 * thread inside the repository under the foreground-service pattern — and surface the
 * honest "X done, Y failed" summary via [RecycleBinState.opNotice] (spec §1.6 — one
 * summary per operation, never silent).
 */
class RecycleBinViewModel(
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
    val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<RecycleBinState> =
        combine(repository.recycleBin(), selectedIds, BulkOps.summary) { entries, selected, notice ->
            val validIds = selected.filter { id -> entries.any { it.item.id == id } }.toSet()
            RecycleBinState(entries = entries, selectedIds = validIds, opNotice = notice)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecycleBinState())

    init {
        viewModelScope.launch { repository.purgeExpired(clock()) }
    }

    fun toggle(itemId: String) {
        selectedIds.value =
            if (itemId in selectedIds.value) selectedIds.value - itemId else selectedIds.value + itemId
    }

    fun selectAll() {
        selectedIds.value = state.value.entries.mapTo(mutableSetOf()) { it.item.id }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    /** The screen showed (or dismissed) the summary snackbar — clear the shared slot. */
    fun consumeOpNotice() {
        BulkOps.consume()
    }

    /**
     * Bulk restore back to the vault (W1-E4): index entries return to their albums, the
     * still-encrypted blobs never moved. An entry whose blob has vanished stays in the
     * bin and is reported as failed — never silently dropped.
     */
    fun restoreSelected() =
        mutateSelection { ids ->
            val restored = repository.restore(ids)
            summaryLine("restored", restored, failed = ids.size - restored)
        }

    /** Bulk permanent destruction (secure blob wipe) behind the screen's red confirm. */
    fun deleteSelectedForever() =
        mutateSelection { ids ->
            val deleted = repository.deleteForever(ids)
            summaryLine("deleted forever", deleted, failed = ids.size - deleted)
        }

    /**
     * The bin rows' preview tiles ride the same encrypted stored-thumb pipeline as the
     * grids (spec §1.7 — cached thumbnails only, never a full-grid re-decrypt): the thumb
     * written at hide time survives the trip into the bin, so this is a small decrypt of
     * tens of KB, LRU-cached in memory.
     */
    suspend fun thumbnail(
        context: Context,
        item: VaultItem,
    ): ImageBitmap? = VaultThumbnailPipeline.load(context, item, repository)

    /** Run a bulk op on the current selection via [BulkOps] and clear the selection. */
    private fun mutateSelection(block: suspend (Set<String>) -> String?) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        clearSelection()
        BulkOps.run { block(ids) }
    }

    private companion object {
        /** "3 items restored" / "1 item deleted forever, 2 failed" (spec §1.6 summary). */
        fun summaryLine(
            verb: String,
            done: Int,
            failed: Int,
        ): String {
            val counted = if (done == 1) "1 item" else "$done items"
            return if (failed > 0) "$counted $verb, $failed failed" else "$counted $verb"
        }
    }
}
