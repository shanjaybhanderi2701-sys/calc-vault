package com.appblish.calculatorvault.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Recycle-bin state: surviving entries plus the current multi-select. */
data class RecycleBinState(
    val entries: List<RecycleBinEntry> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * Backs the recycle-bin screen. Purges anything past the auto-delete window on open,
 * then exposes the surviving entries with per-item "N days left". Restore returns items
 * to their category; delete-forever destroys the blob (guard behind a destructive
 * confirm in the UI).
 */
class RecycleBinViewModel(
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
    val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<RecycleBinState> =
        combine(repository.recycleBin(), selectedIds) { entries, selected ->
            val validIds = selected.filter { id -> entries.any { it.item.id == id } }.toSet()
            RecycleBinState(entries = entries, selectedIds = validIds)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecycleBinState())

    init {
        viewModelScope.launch { repository.purgeExpired(clock()) }
    }

    fun toggle(itemId: String) {
        selectedIds.value =
            if (itemId in selectedIds.value) selectedIds.value - itemId else selectedIds.value + itemId
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun restoreSelected() = mutate { repository.restore(it) }

    fun deleteSelectedForever() = mutate { repository.deleteForever(it) }

    private fun mutate(block: suspend (Set<String>) -> Unit) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            block(ids)
            clearSelection()
        }
    }
}
