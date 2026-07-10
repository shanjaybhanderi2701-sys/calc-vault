package com.appblish.calculatorvault.vault.viewer

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.VaultContentRepository
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.actions.AlbumOption
import com.appblish.calculatorvault.vault.actions.UnhideMessages
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolves a single [VaultItem] by id for the viewer, decrypts its blob for display, and
 * hosts its single-photo actions — Move / Unhide / Delete (Bin + secure Permanent) /
 * Property (W1-E2). Observes the shared repository so a change elsewhere closes the viewer
 * (item goes null).
 *
 * Decryption routing (spec §7): images/contacts stay purely in memory ([decrypted]).
 * Video/audio are **not** decrypted here — playback streams the encrypted blob through the
 * seekable decrypting DataSource in the pager viewer (APP-347), so no plaintext ever
 * touches disk (§1.1); this single-item VM has no player and does nothing for those kinds.
 *
 * The mutating actions run on [viewModelScope] (off the main thread inside the repository,
 * spec §1.6). Move/Delete/Unhide surface a one-shot [message] for the design's §7 result
 * snackbar; [restore] additionally surfaces the per-operation outcome via a Toast (D-3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModel(
    private val itemId: String,
    context: Context? = null,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
) : ViewModel() {
    // Application context only — needed for the cache dir and the restore-outcome Toast.
    private val appContext: Context? = context?.applicationContext

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

    /** Decrypted blob bytes (images/contacts); null while loading or if the blob is missing. */
    val decrypted: StateFlow<ByteArray?> = _decrypted.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** One-shot result copy for a snackbar (unhide summary, move/delete confirmation). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    init {
        viewModelScope.launch {
            // Wait for the item so we know its category before choosing the decrypt route.
            val current = item.filterNotNull().first()
            when (current.category) {
                VaultCategory.VIDEOS, VaultCategory.AUDIOS -> {
                    // APP-347: video/audio playback now STREAMS the encrypted blob through the
                    // seekable decrypting DataSource (see PagerViewerScreen.MediaPlayerPage) —
                    // no plaintext temp file is ever written (§1.1). This single-item VM is not
                    // wired to a player, so it deliberately does nothing for media here.
                }
                else -> {
                    _decrypted.value = withContext(Dispatchers.IO) { repository.openDecrypted(itemId) }
                }
            }
        }
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

    /** Send this item to the recycle bin (the safe default of the delete-choice modal). */
    fun moveToRecycleBin() {
        viewModelScope.launch {
            // NonCancellable: the caller pops back immediately, which clears this VM —
            // the mutation must still complete.
            withContext(NonCancellable) { repository.moveToRecycleBin(setOf(itemId)) }
        }
    }

    /** Soft delete → recycle bin (recoverable). Reports the §7 result copy. */
    fun delete() {
        viewModelScope.launch {
            withContext(NonCancellable) { repository.moveToRecycleBin(setOf(itemId)) }
            _message.value = "Moved to Recycle Bin."
        }
    }

    /** Destroy this item outright (the explicit choice in the modal is the consent, D-4). */
    fun deletePermanently() {
        viewModelScope.launch {
            // deleteForever is contractually scoped to recycle-bin entries, so a live vault
            // item passes through the bin first — same end state, no bin residue.
            withContext(NonCancellable) {
                repository.moveToRecycleBin(setOf(itemId))
                repository.deleteForever(setOf(itemId))
            }
        }
    }

    /** Secure permanent delete straight from the vault (wipe blob + drop index entry). */
    fun permanentlyDelete() {
        viewModelScope.launch {
            withContext(NonCancellable) { repository.permanentlyDelete(setOf(itemId)) }
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

    /**
     * Restore this item: decrypt it back to public storage so it returns to the gallery,
     * then remove it from the vault. The outcome (restored / fallback folder / failed) is
     * never silent — the [com.appblish.calculatorvault.vault.model.RestoreSummary] notice
     * is shown as a Toast because the viewer pops back immediately (design call D-3).
     */
    fun restore() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                val summary = repository.unhideDetailed(setOf(itemId))
                val notice = summary.noticeText()
                if (notice != null && appContext != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, notice, Toast.LENGTH_LONG).show()
                    }
                }
            }
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
