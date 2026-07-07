package com.appblish.calculatorvault.vault.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.VaultContentRepository
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.actions.AlbumOption
import com.appblish.calculatorvault.vault.actions.UnhideMessages
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Resolves a single [VaultItem] by id for the viewer, and hosts its single-photo actions —
 * Move / Unhide / Delete (Bin + secure Permanent) / Property (W1-E2). Observes the shared
 * repository so a change elsewhere closes the viewer (item goes null). Also decrypts the
 * item's blob once so the viewer renders the real bytes rather than a placeholder.
 *
 * The mutating actions run on [viewModelScope] (off the main thread inside the repository,
 * spec §1.6) and surface a one-shot [message] for the design's §7 result snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModel(
    private val itemId: String,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
) : ViewModel() {
    val item: StateFlow<VaultItem?> =
        repository
            .allItems()
            .map { list -> list.firstOrNull { it.id == itemId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Move targets for this item's category: an "Album root" option plus every folder. */
    val albums: StateFlow<List<AlbumOption>> =
        item
            .filterNotNull()
            .flatMapLatest { current ->
                combine(repository.folders(current.category), repository.items(current.category)) { folders, items ->
                    listOf(AlbumOption(null, "Album root", items.count { it.folderId == null })) +
                        folders.map { f -> AlbumOption(f.id, f.name, items.count { it.folderId == f.id }) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Name of the folder this item currently lives in (for the Property "In vault" row). */
    val albumName: StateFlow<String?> =
        item
            .filterNotNull()
            .flatMapLatest { current ->
                repository.folders(current.category).map { fs -> fs.firstOrNull { it.id == current.folderId }?.name }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _decrypted = MutableStateFlow<ByteArray?>(null)

    /** Decrypted blob bytes for [itemId]; null while loading or if the blob is missing. */
    val decrypted: StateFlow<ByteArray?> = _decrypted.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** One-shot result copy for a snackbar (unhide summary, move/delete confirmation). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    init {
        viewModelScope.launch { _decrypted.value = repository.openDecrypted(itemId) }
    }

    /** Move this item's index entry to [folderId] (null = album root) — blob stays encrypted. */
    fun move(folderId: String?) {
        viewModelScope.launch {
            repository.moveToFolder(setOf(itemId), folderId)
            _message.value = "Moved."
        }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val category = item.value?.category ?: return
        viewModelScope.launch { repository.createFolder(category, trimmed) }
    }

    /** Soft delete → recycle bin (recoverable). */
    fun delete() {
        viewModelScope.launch {
            repository.moveToRecycleBin(setOf(itemId))
            _message.value = "Moved to Recycle Bin."
        }
    }

    /** Secure permanent delete straight from the vault (wipe blob + drop index entry). */
    fun permanentlyDelete() {
        viewModelScope.launch {
            repository.permanentlyDelete(setOf(itemId))
            _message.value = "Deleted permanently."
        }
    }

    /**
     * Un-hide this item to [destination]: decrypt back to public storage (with the §1.4
     * Downloads fallback) then drop it from the vault. Reports the honest §7 result copy.
     */
    fun unhide(destination: UnhideDestination = UnhideDestination.Original) {
        viewModelScope.launch {
            val result = repository.unhideTo(setOf(itemId), destination)
            _message.value = UnhideMessages.summary(result)
        }
    }
}

/** Feeds the folder slideshow with a category's items (folder scoping lands with folders UI). */
class SlideshowViewModel(
    private val category: VaultCategory,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
) : ViewModel() {
    val items: StateFlow<List<VaultItem>> =
        repository
            .items(category)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
