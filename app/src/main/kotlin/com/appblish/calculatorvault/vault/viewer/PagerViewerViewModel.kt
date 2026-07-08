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
import com.appblish.calculatorvault.vault.model.sortItems
import com.appblish.calculatorvault.vault.share.VaultShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
        combine(
            repository.items(category),
            repository.folders(category),
            repository.sortPrefs(),
        ) { all, folders, prefs ->
            // The pager's page order equals the grid the user tapped (W3-E §7): the same
            // effective photo sort — the open album's override when present, else the
            // vault-wide persisted choice.
            val effectiveSort =
                folders.firstOrNull { it.id == folderId }?.photoSortOverride ?: prefs.photoSort
            sortItems(all.filter(::inContext), effectiveSort)
        }.map { pages ->
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
    private val activeItemId = MutableStateFlow<String?>(null)

    /** The settled page's live [VaultItem] (drives the §6 Move sheet + §9 Property dialog). */
    val activeItem: StateFlow<VaultItem?> =
        activeItemId
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
        // Page change is a rotate-commit boundary (W3-D §8): flush the previous page's
        // pending net orientation before this page takes over — never lost on swipe.
        commitPendingRotation()
        decryptJob?.cancel()
        tempFile?.delete()
        tempFile = null
        // Retarget the §6–§9 single-photo actions at the newly settled page.
        activeItemId.value = itemId
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
        val id = activeItemId.value ?: return
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
        val id = activeItemId.value ?: return
        viewModelScope.launch {
            val result = withContext(NonCancellable) { repository.unhideTo(setOf(id), destination) }
            _message.value = UnhideMessages.summary(result)
        }
    }

    /** §8 · Soft delete the active item → recycle bin (recoverable). */
    fun delete() {
        val id = activeItemId.value ?: return
        viewModelScope.launch {
            withContext(NonCancellable) { repository.moveToRecycleBin(setOf(id)) }
            _message.value = "Moved to Recycle Bin."
        }
    }

    /** §8 · Secure permanent delete of the active item straight from the vault. */
    fun permanentlyDelete() {
        val id = activeItemId.value ?: return
        viewModelScope.launch {
            withContext(NonCancellable) { repository.permanentlyDelete(setOf(id)) }
            _message.value = "Deleted permanently."
        }
    }

    // --- APP-294 · Share (vault-safe temp-copy contract, see [VaultShare]) -------------

    private val _shareRequest = MutableStateFlow<VaultShare.Session?>(null)

    /** A prepared share awaiting launch; the screen's ShareSessionLauncher consumes it. */
    val shareRequest: StateFlow<VaultShare.Session?> = _shareRequest.asStateFlow()

    // The launched-but-not-finished session, kept so the activity-result callback can
    // purge its temp copies the moment the share flow returns (complete or cancelled).
    private var liveShareSession: VaultShare.Session? = null

    /**
     * Share the settled page: stream-decrypt it to a scoped temp copy and surface the
     * session via [shareRequest]. Reads the target from [activeItemId] + a fresh
     * repository lookup (the [activeItem] flow only produces while collected — same
     * rule as [setAsCover]). One share flow at a time; a decrypt failure is never
     * silent ([message] shows "Couldn't share.").
     */
    fun share() {
        if (liveShareSession != null || _shareRequest.value != null) return
        val id = activeItemId.value ?: return
        val context = appContext ?: return
        viewModelScope.launch {
            val item = repository.allItems().first().firstOrNull { it.id == id }
            val session =
                item?.let {
                    withContext(Dispatchers.IO) { VaultShare.prepare(context, repository, listOf(it)) }
                }
            if (session == null) {
                _message.value = "Couldn't share."
            } else {
                liveShareSession = session
                _shareRequest.value = session
            }
        }
    }

    /** The screen launched the chooser; consume the request so it can never re-fire. */
    fun shareLaunched() {
        _shareRequest.value = null
    }

    /**
     * The share flow returned (completed or cancelled) — delete the temp copy now, per
     * the APP-294 contract. NonCancellable on IO so a simultaneous VM clear (back press
     * racing the result) can't strand the cleartext until the process-restart purge.
     */
    fun shareFinished() {
        val session = liveShareSession ?: return
        liveShareSession = null
        commitScope.launch { VaultShare.purge(session) }
    }

    // --- W3-E §5 · Set as cover (viewer `⋯ More`) --------------------------------------

    /**
     * Make the active page its album's cover — an index pointer write (spec §2.7). The
     * album is implicit (the photo's own); the changed home tile is off-screen, so the
     * snackbar *is* required here (contrast the pin's object-is-the-interface rule).
     * Idempotent: re-setting the current cover shows the same copy, writes nothing new.
     *
     * Reads the target from [activeItemId] + a fresh repository lookup — like every other
     * §6–§9 action — rather than the [activeItem] StateFlow, which only produces while a
     * subscriber is collecting it (the host's PhotoActionsHost); the `⋯ More` menu must
     * work regardless of what else is observing.
     */
    fun setAsCover() {
        val id = activeItemId.value ?: return
        viewModelScope.launch {
            val item = repository.allItems().first().firstOrNull { it.id == id }
            val folderId = item?.folderId
            if (folderId == null) {
                // The bar hides `⋯ More` for a folder-less item, so this is defensive only.
                _message.value = "Couldn't set cover — try again."
                return@launch
            }
            val ok =
                runCatching { withContext(NonCancellable) { repository.setFolderCover(folderId, id) } }
                    .isSuccess
            _message.value = if (ok) "Set as album cover." else "Couldn't set cover — try again."
        }
    }

    // --- W3-E §8 · Rotate persistence -----------------------------------------------

    // The one un-committed rotation: the settled page's item id and its pending net
    // orientation (mod 360). Commits per rotate.commitDebounce — 500ms idle, page
    // change, or viewer exit, whichever first. Four taps = net 0 vs stored = no write.
    private var pendingRotation: Pair<String, Int>? = null
    private var rotationDebounce: Job? = null

    /**
     * The screen reports every rotate tap here with the resulting **net** orientation.
     * Restarts the 500ms idle debounce; the commit itself is a single index write plus a
     * one-thumbnail re-derivation in the repository.
     */
    fun noteRotation(
        itemId: String,
        netDegrees: Int,
    ) {
        pendingRotation = itemId to (((netDegrees % 360) + 360) % 360)
        rotationDebounce?.cancel()
        rotationDebounce =
            viewModelScope.launch {
                delay(ROTATE_COMMIT_DEBOUNCE_MS)
                commitPendingRotation()
            }
    }

    /**
     * Flush the pending rotation now (debounce fire, page change, viewer exit). Launched
     * on [commitScope] — not viewModelScope — so the exit-path commit still lands after
     * the VM is cleared (one small index write + one thumb, off-main; W3-D §8).
     */
    private fun commitPendingRotation() {
        val (itemId, degrees) = pendingRotation ?: return
        pendingRotation = null
        rotationDebounce?.cancel()
        commitScope.launch {
            val persisted = runCatching { repository.setRotation(itemId, degrees) }.getOrDefault(false)
            if (!persisted) {
                // Commit failure is rare and non-destructive: the in-session view stays
                // rotated; surface the retryable miss (W3-D §8 failure copy).
                _message.value = "Couldn't save rotation."
            }
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
    // Viewer exit is also the last rotate-commit boundary (W3-D §8): the flush runs on
    // [commitScope] because viewModelScope is already cancelled here.
    override fun onCleared() {
        commitPendingRotation()
        decryptJob?.cancel()
        createdTempFiles.forEach { it.delete() }
        // Share backstop: a VM clear mid-share (ON_STOP re-lock popping the viewer while
        // the receiver is foreground) would lose the activity-result purge — purge here.
        // An already-open receiver stream survives the unlink; a later open fails closed.
        shareFinished()
        super.onCleared()
    }

    private companion object {
        const val VIEWER_CACHE_DIR = "viewer"

        /** rotate.commitDebounce (W3-D §3): 500ms idle, or page-change/exit first. */
        const val ROTATE_COMMIT_DEBOUNCE_MS = 500L

        // Process-scoped home for rotate commits so an exit-path flush survives the VM
        // clear — mirrors BulkOps' app-scoped pattern for must-complete index writes.
        val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
