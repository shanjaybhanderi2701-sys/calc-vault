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
import kotlinx.coroutines.launch

/** A category screen's state: its items, folders, and the multi-select ("pinch") set. */
data class CategoryState(
    val category: VaultCategory,
    val items: List<VaultItem> = emptyList(),
    val folders: List<VaultFolder> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
) {
    val isEmpty: Boolean get() = items.isEmpty() && folders.isEmpty()
}

/**
 * Backs one category screen (Photos/Videos/Audios/Files/Contacts). Owns the transient
 * selection state locally and delegates every mutation — recycle-bin, folder move,
 * create folder — to the shared [VaultContentRepository], so other screens see the
 * change immediately.
 */
class CategoryViewModel(
    val category: VaultCategory,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
) : ViewModel() {
    private val selection = MutableStateFlow(SelectionState())

    private data class SelectionState(
        val ids: Set<String> = emptySet(),
        val active: Boolean = false,
    )

    val state: StateFlow<CategoryState> =
        combine(
            repository.items(category),
            repository.folders(category),
            selection,
        ) { items, folders, sel ->
            // Drop selections that no longer exist (e.g. after a recycle-bin move).
            val validIds = sel.ids.filter { id -> items.any { it.id == id } }.toSet()
            CategoryState(
                category = category,
                items = items,
                folders = folders,
                selectedIds = validIds,
                selectionMode = sel.active && validIds.isNotEmpty(),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CategoryState(category),
        )

    /** Long-press starts selection mode with the pressed item selected. */
    fun startSelection(itemId: String) {
        selection.value = SelectionState(ids = setOf(itemId), active = true)
    }

    /** Tap while selecting toggles membership; leaving the set empty exits the mode. */
    fun toggle(itemId: String) {
        val current = selection.value
        if (!current.active) return
        val ids = if (itemId in current.ids) current.ids - itemId else current.ids + itemId
        selection.value = current.copy(ids = ids, active = ids.isNotEmpty())
    }

    fun clearSelection() {
        selection.value = SelectionState()
    }

    /**
     * Decode a grid thumbnail for [itemId] from its decrypted blob (image bitmap / video
     * frame; null for non-visual categories). Runs off the caller's composition on IO.
     */
    suspend fun thumbnail(
        context: Context,
        itemId: String,
    ): ImageBitmap? {
        val item = state.value.items.firstOrNull { it.id == itemId } ?: return null
        return VaultThumbnails.forItem(context, item) { repository.openDecrypted(itemId) }
    }

    fun recycleSelected() = mutateSelection { repository.moveToRecycleBin(it) }

    fun moveSelectedToFolder(folderId: String?) = mutateSelection { repository.moveToFolder(it, folderId) }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createFolder(category, trimmed) }
    }

    private fun mutateSelection(block: suspend (Set<String>) -> Unit) {
        val ids = selection.value.ids
        if (ids.isEmpty()) return
        viewModelScope.launch {
            block(ids)
            clearSelection()
        }
    }
}
