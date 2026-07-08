package com.appblish.calculatorvault.vault.viewer

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.CategoryState
import com.appblish.calculatorvault.vault.VaultContentRepository
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.actions.AlbumOption
import com.appblish.calculatorvault.vault.actions.UnhideMessages
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import java.io.File
import java.util.UUID

/**
 * The pager viewer's page set: every item of the opened context (folder or category
 * root), in the same newest-first order the category grid showed. [startIndex] is the
 * position of the tapped item, latched on the first emission that contains any pages so
 * later list changes (deletes) never yank the pager back to the start item's old slot.
 */
data class PagerViewerState(
    val pages: List<VaultItem> = emptyList(),
    val startIndex: Int = 0,
    val loaded: Boolean = false,
) {
    /** True once the context loaded and holds no items — the screen should navigate back. */
    val empty: Boolean get() = loaded && pages.isEmpty()
}

/**
 * Decrypted payload of the pager's current page. Every page resolves to exactly one of
 * these — [Error] (never a silent blank screen, the board's P0-2 complaint) when the
 * item, blob, key, or cache dir is missing.
 */
sealed interface PageContent {
    /** Decrypt still in flight — the page shows a small progress indicator. */
    data object Loading : PageContent

    /** In-memory decrypted blob for image/file/contact pages; the screen decodes off-main. */
    class Bytes(
        val bytes: ByteArray,
    ) : PageContent

    /** App-private cache temp file holding a decrypted video/audio blob for ExoPlayer. */
    class Media(
        val file: File,
    ) : PageContent

    /** Decrypt failed — the page shows an error glyph + "Couldn't open this file". */
    data object Error : PageContent
}

/** The one page whose blob is currently decrypted (only the settled page ever is). */
data class ActivePage(
    val itemId: String,
    val content: PageContent,
)

/**
 * Backs the gallery-grade swipeable viewer (APP-225 board feedback, P0-2): the page set
 * is the full context the user opened the item from — a folder's items, or the category
 * root's folder-less items ([folderId] null / the "Recent" pseudo-folder) — filtered
 * exactly like [com.appblish.calculatorvault.vault.CategoryViewModel]'s folder scoping so
 * the pager equals the grid. Observing the live repository flow means a delete simply
 * shrinks [PagerViewerState.pages] (the pager advances naturally); an emptied context is
 * signalled via [PagerViewerState.empty].
 *
 * Decryption stays strictly per-page ([setActivePage], spec §7): images/contacts/files
 * are decrypted purely in memory; video/audio stream to a random-named, extension-less
 * temp file under the app-private cache (`cacheDir/viewer/`) for ExoPlayer. Swiping away
 * deletes the previous temp file immediately; [onCleared] deletes every temp file this
 * session created as the backstop — decrypted bytes never touch public storage.
 *
 * Delete/Restore keep the single-item viewer's semantics: [deletePermanently] routes
 * through the recycle bin + deleteForever under [NonCancellable] (D-4), and [restore]
 * surfaces the per-operation outcome as a Toast (D-3).
 *
 * It also hosts the W1-E2 §6–§9 single-photo actions for the **currently active page**:
 * [move]/[createFolder] (§6), [unhide] (§7), [delete]/[permanentlyDelete] (§8), and the
 * Property accessors [activeItem]/[albumName]/[albums] (§9). Those surface a one-shot
 * [message] the pager route shows as a §7 snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PagerViewerViewModel(
    private val startItemId: String,
    val category: VaultCategory,
    private val folderId: String?,
    context: Context? = null,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
) : ViewModel() {
    // Application context only — needed for the cache dir and the restore-outcome Toast.
    private val appContext: Context? = context?.applicationContext

    // Latched once so the start page is resolved exactly one time (see PagerViewerState).
    private var latchedStartIndex: Int? = null

    val state: StateFlow<PagerViewerState> =
        repository
            .items(category)
            .map { all -> all.filter(::inContext) }
            .map { pages ->
                if (latchedStartIndex == null && pages.isNotEmpty()) {
                    // A missing start item (already deleted) falls back to the first page.
                    latchedStartIndex = pages.indexOfFirst { it.id == startItemId }.coerceAtLeast(0)
                }
                PagerViewerState(pages = pages, startIndex = latchedStartIndex ?: 0, loaded = true)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagerViewerState())

    private val _activePage = MutableStateFlow<ActivePage?>(null)

    /** The settled page's decrypted content; pages other than [ActivePage.itemId] show a spinner. */
    val activePage: StateFlow<ActivePage?> = _activePage.asStateFlow()

    // The currently settled page's item id — the target of every §6–§9 single-photo action.
    private val _activeItemId = MutableStateFlow<String?>(null)

    /** The settled page's live [VaultItem] (drives the §6 Move sheet + §9 Property dialog). */
    val activeItem: StateFlow<VaultItem?> =
        _activeItemId
            .flatMapLatest { id ->
                if (id == null) {
                    MutableStateFlow(null)
                } else {
                    repository.allItems().map { all -> all.firstOrNull { it.id == id } }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Move targets for the active item's category: an "Album root" option plus every folder. */
    val albums: StateFlow<List<AlbumOption>> =
        activeItem
            .filterNotNull()
            .flatMapLatest { current ->
                combine(repository.folders(current.category), repository.items(current.category)) { folders, items ->
                    listOf(AlbumOption(null, "Album root", items.count { it.folderId == null })) +
                        folders.map { f -> AlbumOption(f.id, f.name, items.count { it.folderId == f.id }) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Name of the folder the active item currently lives in (Property "In vault" row). */
    val albumName: StateFlow<String?> =
        activeItem
            .filterNotNull()
            .flatMapLatest { current ->
                repository.folders(current.category).map { fs -> fs.firstOrNull { it.id == current.folderId }?.name }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)

    /** One-shot result copy for the §7 snackbar (unhide summary, move/delete confirmation). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    private var decryptJob: Job? = null
    private var tempFile: File? = null

    // Every temp file this session created, so onCleared can also sweep up a file whose
    // decrypt job was cancelled mid-write (IO may finish the write after the cancel).
    private val createdTempFiles = mutableListOf<File>()

    /**
     * Make [itemId] the one decrypted page. Called by the screen when the pager settles on
     * a page; the previous page's decrypt is cancelled and its temp file deleted before the
     * new decrypt starts, so at most one cleartext blob exists at a time.
     */
    fun setActivePage(itemId: String) {
        if (_activePage.value?.itemId == itemId) return
        decryptJob?.cancel()
        tempFile?.delete()
        tempFile = null
        // Retarget the §6–§9 single-photo actions at the newly settled page.
        _activeItemId.value = itemId
        _activePage.value = ActivePage(itemId, PageContent.Loading)
        decryptJob =
            viewModelScope.launch {
                val item = repository.allItems().first().firstOrNull { it.id == itemId }
                val content =
                    when (item?.category) {
                        null -> PageContent.Error
                        VaultCategory.VIDEOS, VaultCategory.AUDIOS -> decryptToCache(itemId)
                        else -> {
                            val bytes = withContext(Dispatchers.IO) { repository.openDecrypted(itemId) }
                            if (bytes != null) PageContent.Bytes(bytes) else PageContent.Error
                        }
                    }
                // Publish only if this page is still the active one (guards a stale decrypt
                // finishing after a fast swipe re-keyed the active page).
                if (_activePage.value?.itemId == itemId) {
                    _activePage.value = ActivePage(itemId, content)
                }
            }
    }

    /** Send [itemId] to the recycle bin (the safe default of the delete-choice modal). */
    fun moveToRecycleBin(itemId: String) {
        viewModelScope.launch {
            // NonCancellable: the mutation must complete even if the screen leaves and
            // clears this VM mid-flight.
            withContext(NonCancellable) { repository.moveToRecycleBin(setOf(itemId)) }
        }
    }

    /** Destroy [itemId] outright (the explicit choice in the modal is the consent, D-4). */
    fun deletePermanently(itemId: String) {
        viewModelScope.launch {
            // deleteForever is contractually scoped to recycle-bin entries, so a live vault
            // item passes through the bin first — same end state, no bin residue.
            withContext(NonCancellable) {
                repository.moveToRecycleBin(setOf(itemId))
                repository.deleteForever(setOf(itemId))
            }
        }
    }

    /**
     * Restore [itemId] back to public storage. The outcome (restored / fallback folder /
     * failed) is never silent — the [com.appblish.calculatorvault.vault.model.RestoreSummary]
     * notice is shown as a Toast because the pager keeps running while the list shrinks
     * (design call D-3 allows this surface for the viewer path).
     */
    fun restore(itemId: String) {
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

    // --- W1-E2 §6–§9 single-photo actions, keyed to the active page (see [activeItem]) ---

    /** §6 · Move the active item's index entry to [folderId] (null = album root). */
    fun move(folderId: String?) {
        val id = _activeItemId.value ?: return
        viewModelScope.launch {
            withContext(NonCancellable) { repository.moveToFolder(setOf(id), folderId) }
            _message.value = "Moved."
        }
    }

    /** §6 · Create a folder in the active item's category (Move sheet "New folder…"). */
    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val category = activeItem.value?.category ?: return
        viewModelScope.launch { repository.createFolder(category, trimmed) }
    }

    /**
     * §7 · Un-hide the active item to [destination] (with the §1.4 Downloads fallback), then
     * drop it from the vault. Reports the honest §7 result copy via [message].
     */
    fun unhide(destination: UnhideDestination = UnhideDestination.Original) {
        val id = _activeItemId.value ?: return
        viewModelScope.launch {
            val result = withContext(NonCancellable) { repository.unhideTo(setOf(id), destination) }
            _message.value = UnhideMessages.summary(result)
        }
    }

    /** §8 · Soft delete the active item → recycle bin (recoverable). */
    fun delete() {
        val id = _activeItemId.value ?: return
        viewModelScope.launch {
            withContext(NonCancellable) { repository.moveToRecycleBin(setOf(id)) }
            _message.value = "Moved to Recycle Bin."
        }
    }

    /** §8 · Secure permanent delete of the active item straight from the vault. */
    fun permanentlyDelete() {
        val id = _activeItemId.value ?: return
        viewModelScope.launch {
            withContext(NonCancellable) { repository.permanentlyDelete(setOf(id)) }
            _message.value = "Deleted permanently."
        }
    }

    /** True when [item] belongs to the opened context (mirrors CategoryState.folderItems). */
    private fun inContext(item: VaultItem): Boolean =
        when (folderId) {
            null, CategoryState.RECENT_FOLDER_ID -> item.folderId == null
            else -> item.folderId == folderId
        }

    private suspend fun decryptToCache(itemId: String): PageContent {
        val cache = appContext?.cacheDir ?: return PageContent.Error
        val target = File(File(cache, VIEWER_CACHE_DIR), UUID.randomUUID().toString())
        tempFile = target
        createdTempFiles += target
        // Stream blob → cipher → file so a large video is never held in memory (spec §11);
        // decryptToFile deletes any partial file on failure.
        val ok =
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                repository.decryptToFile(itemId, target)
            }
        return if (ok) PageContent.Media(target) else PageContent.Error
    }

    // Backstop cleanup: page changes delete files eagerly, but a VM clear (back press,
    // process re-lock) must not strand cleartext in the cache — sweep everything created.
    override fun onCleared() {
        decryptJob?.cancel()
        createdTempFiles.forEach { it.delete() }
        super.onCleared()
    }

    private companion object {
        const val VIEWER_CACHE_DIR = "viewer"
    }
}
