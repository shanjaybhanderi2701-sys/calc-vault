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
import kotlin.coroutines.CoroutineContext

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

    /**
     * A video/audio page. Carries only the item id — playback **streams** the encrypted blob
     * through the seekable decrypting DataSource (APP-347), so nothing is decrypted here and
     * no plaintext temp file is ever written (§1.1). [hasVideoFrame] is true for video (a
     * first-frame preview is shown), false for audio (play button over the canvas only).
     */
    class Media(
        val itemId: String,
        val hasVideoFrame: Boolean,
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
 * Decryption stays per-page and in-session ([setActivePage], spec §7): images/contacts/
 * files are decrypted purely in memory; video/audio stream to a random-named,
 * extension-less temp file under the app-private cache (`cacheDir/viewer/`) for ExoPlayer.
 * Once decrypted, a page's payload joins the **in-session viewer cache** (APP-293 P0-4) —
 * a byte-capped LRU for photo bytes and a count-capped set of media temp files — so
 * swiping back to a viewed page is instant and never decrypts twice. [onCleared] clears
 * the byte cache and deletes every temp file this session created — decrypted bytes never
 * touch public storage and never outlive the viewer.
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
    // The decrypt off-main hop, injectable so a unit test can pass its own test scheduler
    // (StandardTestDispatcher/UnconfinedTestDispatcher). Under the real dispatcher the
    // decrypt runs on IO; under the test dispatcher advanceUntilIdle() fully awaits the
    // decrypt + its cacheBytes/decryptJobs.remove continuation, making the fullDecrypts
    // dedup deterministic (APP-317: the P0 proof was flaky on a hard-coded Dispatchers.IO).
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
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

    /** The settled page's decrypted content (drives rotate-enable + mirrored in [pageWindow]). */
    val activePage: StateFlow<ActivePage?> = _activePage.asStateFlow()

    // --- APP-314 P0 · decrypted window {n-1, n, n+1} -----------------------------------
    // The board's on-device miss was a *visible re-load* on every forward slide. The old
    // single-active-page model only ever held the settled page's bytes, so a composed
    // neighbour (beyondViewportPageCount = 1) had nothing but PageContent.Loading until the
    // user landed on it → spinner → decrypt → decode on arrival. The fix pre-decrypts BOTH
    // neighbours the moment the pager settles, so their pages already carry Bytes before the
    // swipe. The screen renders each page from this map (Loading only for a page outside the
    // window); the byte/media LRU below is the swipe-back reuse + memory backstop.
    private val _pageWindow = MutableStateFlow<Map<String, PageContent>>(emptyMap())
    val pageWindow: StateFlow<Map<String, PageContent>> = _pageWindow.asStateFlow()

    // The settled page id (window anchor) and the {n-1,n,n+1} id set currently kept decrypted.
    private var settledId: String? = null
    private var windowIds: Set<String> = emptySet()

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

    // One in-flight decrypt per windowed item; neighbours are never cancelled on settle —
    // only pages that fall out of the window are (see [setActivePage]).
    private val decryptJobs = mutableMapOf<String, Job>()

    // --- APP-293 P0-4 · in-session decrypted-page cache ---------------------------------
    // Swiping back to an already-viewed page must be instant and must not decrypt again
    // (the fullDecrypts discipline: reuse in-session plaintext, no unnecessary decrypts).
    // Photo/file bytes sit in a byte-capped access-order LRU; video/audio keep their
    // decrypted temp files for the session (count-capped — evicted files delete on the
    // spot). Main-thread only (all callers are viewModelScope on Main).
    private val byteCache = LinkedHashMap<String, PageContent.Bytes>(16, 0.75f, true)
    private var byteCacheSize = 0L

    // Video/audio pages carry only an item id ([PageContent.Media]) and stream on demand, so
    // there is nothing heavy to cache — a re-settled media page is rebuilt for free (APP-347,
    // which retired the decrypted temp-file cache the old mediaCache held).
    private fun cachedContent(itemId: String): PageContent? = byteCache[itemId]

    private fun cacheBytes(
        itemId: String,
        content: PageContent.Bytes,
    ) {
        byteCache.put(itemId, content)?.let { byteCacheSize -= it.bytes.size }
        byteCacheSize += content.bytes.size
        val eldest = byteCache.entries.iterator()
        while (byteCacheSize > BYTE_CACHE_CAP_BYTES && eldest.hasNext()) {
            val entry = eldest.next()
            if (entry.key == itemId) continue // never evict the page being viewed
            byteCacheSize -= entry.value.bytes.size
            eldest.remove()
        }
    }

    /** Drop [itemId]'s cached plaintext the moment it leaves the vault context. */
    private fun evictFromCache(itemId: String) {
        byteCache.remove(itemId)?.let { byteCacheSize -= it.bytes.size }
    }

    /**
     * Settle the pager on [itemId]: decrypt it **and** proactively pre-decrypt its neighbours
     * n-1 / n+1 into the [pageWindow] (bounded, no cancel of the neighbours themselves), so a
     * forward or backward swipe finds Bytes already in hand — never a Loading spinner. Pages
     * that fall outside the new window have their in-flight decrypt cancelled and their window
     * entry dropped; the byte/media LRU still holds their plaintext for an instant revisit.
     */
    fun setActivePage(itemId: String) {
        if (settledId == itemId) return
        // Page change is a rotate-commit boundary (W3-D §8): flush the previous page's
        // pending net orientation before this page takes over — never lost on swipe.
        commitPendingRotation()
        settledId = itemId
        // Retarget the §6–§9 single-photo actions at the newly settled page.
        activeItemId.value = itemId

        val pages = state.value.pages
        val idx = pages.indexOfFirst { it.id == itemId }
        // The decrypted window {n-1, n, n+1}; when the page set isn't loaded yet (a test
        // driving the VM directly) it degenerates to the settled page alone.
        val windowItems =
            if (idx < 0) {
                listOf(itemId)
            } else {
                listOfNotNull(pages.getOrNull(idx - 1)?.id, itemId, pages.getOrNull(idx + 1)?.id)
            }
        windowIds = windowItems.toSet()

        // Anything no longer in the window: cancel its in-flight decrypt and drop its window
        // entry (the LRU keeps the plaintext for a cheap revisit — this only bounds live work).
        decryptJobs.keys.filter { it !in windowIds }.forEach { decryptJobs.remove(it)?.cancel() }
        _pageWindow.value = _pageWindow.value.filterKeys { it in windowIds }

        // Settled page drives [activePage] immediately (cache hit = no Loading flash).
        val cached = cachedContent(itemId)
        if (cached != null) {
            _activePage.value = ActivePage(itemId, cached)
            putWindow(itemId, cached)
        } else {
            _activePage.value = ActivePage(itemId, PageContent.Loading)
            decryptInto(itemId)
        }

        // Neighbours: pre-decrypt now so their composed page already holds Bytes on arrival.
        // A video/audio neighbour is NOT stream-decrypted eagerly (that would churn large temp
        // files) — but its [PageContent.Media] descriptor is just an id + a flag, so publish that
        // immediately (APP-428) and the peeked page renders its cached poster thumbnail instead of
        // a spinner. The heavy stream-decrypt still stays lazy, happening only once it is settled.
        windowItems.forEach { neighbourId ->
            if (neighbourId == itemId) return@forEach
            val nCached = cachedContent(neighbourId)
            when {
                nCached != null -> putWindow(neighbourId, nCached)
                isHeavyMedia(neighbourId) -> putWindow(neighbourId, mediaContentFor(neighbourId))
                else -> decryptInto(neighbourId)
            }
        }
    }

    /**
     * The lightweight streamable [PageContent.Media] descriptor for a video/audio page — just the
     * id + `hasVideoFrame`, no decrypt. Lets a peeked neighbour render its cached poster preview
     * (APP-428) without any of the heavy on-settle stream-decrypt.
     */
    private fun mediaContentFor(itemId: String): PageContent {
        val item = state.value.pages.firstOrNull { it.id == itemId } ?: return PageContent.Loading
        return PageContent.Media(itemId, hasVideoFrame = item.category == VaultCategory.VIDEOS)
    }

    /** Put [content] into the window map for [itemId] (the screen renders pages from here). */
    private fun putWindow(
        itemId: String,
        content: PageContent,
    ) {
        _pageWindow.value = _pageWindow.value + (itemId to content)
    }

    /** True for video/audio — pre-decrypting these eagerly would write large temp files. */
    private fun isHeavyMedia(itemId: String): Boolean {
        val item = state.value.pages.firstOrNull { it.id == itemId } ?: return false
        return item.category == VaultCategory.VIDEOS || item.category == VaultCategory.AUDIOS
    }

    /**
     * Ensure exactly one decrypt runs for [itemId]; on completion cache the plaintext and
     * publish it to the window (and to [activePage] if it is still the settled page). A page
     * already decrypting (launched as a neighbour, then settled onto) is not decrypted twice —
     * its single job publishes to whichever role the page holds when it finishes.
     */
    private fun decryptInto(itemId: String) {
        if (decryptJobs.containsKey(itemId)) return
        val job =
            viewModelScope.launch {
                val content = decryptContent(itemId)
                if (content is PageContent.Bytes) cacheBytes(itemId, content)
                decryptJobs.remove(itemId)
                publish(itemId, content)
            }
        decryptJobs[itemId] = job
    }

    /**
     * Resolve one item to its [PageContent]. Images/contacts/files decrypt fully in memory;
     * video/audio resolve to a **streamable** [PageContent.Media] (just the id) — the actual
     * decrypt happens lazily on ExoPlayer's loader thread via the seekable DataSource
     * (APP-347), so no whole-file decrypt and no plaintext temp file here.
     */
    private suspend fun decryptContent(itemId: String): PageContent {
        val item = repository.allItems().first().firstOrNull { it.id == itemId }
        return when (item?.category) {
            null -> PageContent.Error
            VaultCategory.VIDEOS, VaultCategory.AUDIOS ->
                PageContent.Media(itemId, hasVideoFrame = item.category == VaultCategory.VIDEOS)
            else -> {
                val bytes = withContext(ioDispatcher) { repository.openDecrypted(itemId) }
                if (bytes != null) PageContent.Bytes(bytes) else PageContent.Error
            }
        }
    }

    /** Route a finished decrypt to the window and, if still settled, to [activePage]. */
    private fun publish(
        itemId: String,
        content: PageContent,
    ) {
        if (itemId in windowIds) putWindow(itemId, content)
        if (itemId == settledId) _activePage.value = ActivePage(itemId, content)
    }

    /** Send [itemId] to the recycle bin (the safe default of the delete-choice modal). */
    fun moveToRecycleBin(itemId: String) {
        evictFromCache(itemId)
        viewModelScope.launch {
            // NonCancellable: the mutation must complete even if the screen leaves and
            // clears this VM mid-flight.
            withContext(NonCancellable) { repository.moveToRecycleBin(setOf(itemId)) }
        }
    }

    /** Destroy [itemId] outright (the explicit choice in the modal is the consent, D-4). */
    fun deletePermanently(itemId: String) {
        evictFromCache(itemId)
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
        evictFromCache(itemId)
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
        evictFromCache(id)
        viewModelScope.launch {
            val result = withContext(NonCancellable) { repository.unhideTo(setOf(id), destination) }
            _message.value = UnhideMessages.summary(result)
        }
    }

    /** §8 · Soft delete the active item → recycle bin (recoverable). */
    fun delete() {
        val id = activeItemId.value ?: return
        evictFromCache(id)
        viewModelScope.launch {
            withContext(NonCancellable) { repository.moveToRecycleBin(setOf(id)) }
            _message.value = "Moved to Recycle Bin."
        }
    }

    /** §8 · Secure permanent delete of the active item straight from the vault. */
    fun permanentlyDelete() {
        val id = activeItemId.value ?: return
        evictFromCache(id)
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
                    withContext(ioDispatcher) { VaultShare.prepare(context, repository, listOf(it)) }
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

    // Backstop cleanup: a VM clear (back press, process re-lock) must drop every in-session
    // plaintext. Video/audio no longer decrypt to disk (APP-347 streams them), so there are
    // no temp files to sweep — only the in-memory photo byte cache.
    // Viewer exit is also the last rotate-commit boundary (W3-D §8): the flush runs on
    // [commitScope] because viewModelScope is already cancelled here.
    override fun onCleared() {
        commitPendingRotation()
        decryptJobs.values.forEach { it.cancel() }
        decryptJobs.clear()
        _pageWindow.value = emptyMap()
        byteCache.clear()
        byteCacheSize = 0L
        // Share backstop: a VM clear mid-share (ON_STOP re-lock popping the viewer while
        // the receiver is foreground) would lose the activity-result purge — purge here.
        // An already-open receiver stream survives the unlink; a later open fails closed.
        shareFinished()
        super.onCleared()
    }

    private companion object {
        /** rotate.commitDebounce (W3-D §3): 500ms idle, or page-change/exit first. */
        const val ROTATE_COMMIT_DEBOUNCE_MS = 500L

        /** P0-4 in-session cache cap: ~48 MB of encoded photo bytes (video/audio stream, no cache). */
        const val BYTE_CACHE_CAP_BYTES = 48L * 1024 * 1024

        // Process-scoped home for rotate commits so an exit-path flush survives the VM
        // clear — mirrors BulkOps' app-scoped pattern for must-complete index writes.
        val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
