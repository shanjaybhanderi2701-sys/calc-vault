package com.appblish.calculatorvault.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.media.MediaSource
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pickable source item from public storage, staged for hiding. */
data class SourceItem(
    val id: String,
    val name: String,
    val dateLabel: String,
    val sortKey: Long,
    val albumId: String,
    val albumName: String,
    // Public-storage content Uri of the original (empty for sample/preview data). Used
    // to stream+encrypt the real bytes and to request deletion of the public copy.
    val contentUri: String = "",
    val mimeType: String? = null,
    // Public-storage RELATIVE_PATH of the original (e.g. "DCIM/Camera/"), carried so the
    // vault can un-hide it back to the same album. Empty for sample data or pre-Q rows.
    val relativePath: String = "",
    // Backing fields for the picker's sort menu (xlock parity). sizeBytes = MediaStore SIZE;
    // dateModified = DATE_MODIFIED epoch millis. Both 0 for sample/preview or contacts.
    val sizeBytes: Long = 0L,
    val dateModified: Long = 0L,
)

/**
 * Sort order for the in-vault picker's item grid, mirroring xlock's sort menu. [ADDED_TIME]
 * keeps the real date-section grouping (newest first); the others collapse into a single
 * section ordered by the chosen field, so the grid header reflects the active sort.
 */
enum class PickerSort(
    val label: String,
) {
    ADDED_TIME("Added time"),
    LAST_MODIFIED("Last modified"),
    NAME("Name"),
    SIZE("Size"),
    ;

    companion object {
        /**
         * Map [sources] to the grid's ([id], sectionLabel, sortKey) tuples for this sort.
         * Pure so the ordering is unit-testable. For non-time sorts a synthetic descending
         * key encodes the target order (the grid always renders by sortKey desc within a
         * section): NAME → A→Z, SIZE → largest first, LAST_MODIFIED → most-recent first.
         */
        fun grid(
            sources: List<SourceItem>,
            sort: PickerSort,
        ): List<Triple<String, String, Long>> =
            when (sort) {
                ADDED_TIME -> sources.map { Triple(it.id, it.dateLabel, it.sortKey) }
                LAST_MODIFIED -> sources.map { Triple(it.id, "Last modified", it.dateModified) }
                SIZE -> sources.map { Triple(it.id, "Largest first", it.sizeBytes) }
                NAME ->
                    sources
                        .sortedBy { it.name.lowercase() }
                        .mapIndexed { i, s -> Triple(s.id, "By name (A–Z)", (sources.size - i).toLong()) }
            }
    }
}

/** Hide/import flow state: folder picker (S14) → date-grouped multi-select (S15) → Hide Now. */
data class HideImportState(
    val category: VaultCategory,
    val albums: List<SourceAlbum> = emptyList(),
    val selectedAlbumId: String? = null,
    val sources: List<SourceItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    // S14 folder-level multi-select: whole device folders staged for hiding directly from
    // the folder grid, without opening each one. Holds album ids (never the Recent
    // aggregate — see [selectableFolderIds]).
    val selectedFolderIds: Set<String> = emptySet(),
    // S15 expand affordance: the item currently shown in the full-screen pre-hide preview
    // (null = no preview open). Kept in state so rotation/tests see the same overlay.
    val previewItemId: String? = null,
    val hiding: Boolean = false,
    val done: Boolean = false,
    // Active sort for the item grid (xlock parity sort menu).
    val sort: PickerSort = PickerSort.ADDED_TIME,
    // True once the runtime media/contacts permission was granted and real sources loaded.
    val permissionGranted: Boolean = false,
    // Public-storage Uris whose originals still need deleting (after a successful hide);
    // the screen launches a MediaStore delete-request for these, then calls
    // [onOriginalsRemoved]. Empty when hiding sample data or when nothing needs consent.
    val pendingDeleteUris: List<String> = emptyList(),
) {
    /** Hide Now is armed by folder selection on S14 and by item selection on S15. */
    val hideEnabled: Boolean get() =
        !hiding && if (selectedAlbumId == null) selectedFolderIds.isNotEmpty() else selectedIds.isNotEmpty()

    /** The currently-open album, if any (used for the item-grid header subtitle). */
    val selectedAlbum: SourceAlbum? get() = albums.firstOrNull { it.id == selectedAlbumId }

    /**
     * Top-bar title per the S15 redline: inside a folder the title is the **live** selected
     * count ("Selected - N", including 0 so the count is always visible); the folder grid
     * keeps the category title.
     */
    val pickerTitle: String get() =
        if (selectedAlbumId == null) "Hide ${category.label.lowercase()}" else "Selected - ${selectedIds.size}"

    /**
     * Folder ids the S14 "All" toggle and per-tile circles operate on. The synthetic
     * "Recent" aggregate is excluded: it *is* every bucket, so selecting it alongside real
     * folders would double-count — "all real folders" already means everything.
     */
    val selectableFolderIds: Set<String> get() =
        albums.mapNotNull { album -> album.id.takeUnless { it == SourceAlbum.RECENT_ID } }.toSet()

    /** True when every selectable device folder is staged (drives the S14 "All" check). */
    val allFoldersSelected: Boolean get() =
        selectableFolderIds.isNotEmpty() && selectedFolderIds.containsAll(selectableFolderIds)

    /** True when every visible item is selected (drives the S15 "All" check). */
    val allItemsSelected: Boolean get() =
        sources.isNotEmpty() && selectedIds.containsAll(sources.map { it.id })

    /** The source behind the open full-screen preview, if any. */
    val previewItem: SourceItem? get() = sources.firstOrNull { it.id == previewItemId }
}

/**
 * One album/bucket in the picker (Camera, Screenshots, Downloads…). [coverUri]/[coverMime]
 * back the folder tile's thumbnail (newest item in the bucket); null for sample data or
 * non-visual buckets, in which case the tile falls back to the category icon.
 */
data class SourceAlbum(
    val id: String,
    val name: String,
    val count: Int,
    val coverUri: String? = null,
    val coverMime: String? = null,
) {
    companion object {
        /**
         * Id of the synthetic "Recent" album that aggregates every bucket — xlock's default
         * public / "All Files" folder at the top of the picker. [MediaSource] recognises it
         * and queries with no bucket filter.
         */
        const val RECENT_ID = "__recent__"
    }
}

/**
 * Drives the hide/import flow shared by all categories. On device it loads the real
 * public-storage candidates from [MediaSource] (MediaStore / ContactsContract) once the
 * runtime permission is granted; without a [MediaSource] (Compose preview / tests) it
 * shows deterministic sample data. The user either multi-selects whole device folders on
 * the S14 grid, or opens one and multi-selects date-grouped items (per-section select-all,
 * live "Selected - N" title, pre-hide preview). On **Hide Now** the chosen items are
 * handed to [VaultContentRepository.hide], which streams each original through
 * `VaultCrypto` into an encrypted blob; per the S16 redline each item lands in a vault
 * folder named after its source device bucket (found or created on the fly), so the
 * category screen's folder tiles mirror the source albums. The public originals are then
 * removed via the screen's delete request (see [HideImportState.pendingDeleteUris]).
 */
class HideImportViewModel(
    val category: VaultCategory,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
    private val mediaSource: MediaSource? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState(category, mediaSource))
    val state: StateFlow<HideImportState> = _state.asStateFlow()

    /** Called by the screen after the runtime permission result. */
    fun onPermissionResult(granted: Boolean) {
        if (!granted || mediaSource == null) {
            _state.update { it.copy(permissionGranted = false) }
            return
        }
        viewModelScope.launch {
            val albums = withContext(Dispatchers.IO) { mediaSource.albums(category) }
            _state.update { it.copy(permissionGranted = true, albums = albums) }
        }
    }

    fun selectAlbum(albumId: String) {
        viewModelScope.launch {
            val sources =
                if (mediaSource != null && _state.value.permissionGranted) {
                    withContext(Dispatchers.IO) { mediaSource.sources(category, albumId) }
                } else {
                    sampleSources(category, albumId)
                }
            _state.update {
                it.copy(selectedAlbumId = albumId, sources = sources, selectedIds = emptySet(), previewItemId = null)
            }
        }
    }

    fun toggle(itemId: String) {
        _state.update { current ->
            val ids = if (itemId in current.selectedIds) current.selectedIds - itemId else current.selectedIds + itemId
            current.copy(selectedIds = ids)
        }
    }

    /** S15 "All" toggle: select every visible item, or clear when all are already selected. */
    fun toggleAll() {
        _state.update { current ->
            val all = current.sources.map { it.id }.toSet()
            current.copy(selectedIds = if (current.selectedIds.containsAll(all)) emptySet() else all)
        }
    }

    /**
     * S15 per-section select-all circle: select every item of the section, or deselect the
     * whole section when all of its items are already selected. Other sections' selections
     * are untouched.
     */
    fun toggleSection(itemIds: Collection<String>) {
        if (itemIds.isEmpty()) return
        _state.update { current ->
            val ids = itemIds.toSet()
            val selected =
                if (current.selectedIds.containsAll(ids)) current.selectedIds - ids else current.selectedIds + ids
            current.copy(selectedIds = selected)
        }
    }

    /** S14 per-tile selection circle: stage/unstage one device folder for a bulk hide. */
    fun toggleFolder(albumId: String) {
        if (albumId == SourceAlbum.RECENT_ID) return // aggregate pseudo-folder is not selectable
        _state.update { current ->
            val ids =
                if (albumId in current.selectedFolderIds) {
                    current.selectedFolderIds - albumId
                } else {
                    current.selectedFolderIds + albumId
                }
            current.copy(selectedFolderIds = ids)
        }
    }

    /**
     * S14 "All" toggle: stage every real device folder (the Recent aggregate is excluded —
     * see [HideImportState.selectableFolderIds]), or clear when all are already staged.
     */
    fun toggleAllFolders() {
        _state.update { current ->
            val all = current.selectableFolderIds
            current.copy(selectedFolderIds = if (current.selectedFolderIds.containsAll(all)) emptySet() else all)
        }
    }

    /** Open the full-screen pre-hide preview for [itemId] (S15 expand affordance). */
    fun openPreview(itemId: String) {
        _state.update { it.copy(previewItemId = itemId) }
    }

    /** Dismiss the full-screen preview (tap / system back). */
    fun closePreview() {
        _state.update { it.copy(previewItemId = null) }
    }

    /** Change the item-grid sort order (xlock parity sort menu). */
    fun setSort(sort: PickerSort) {
        _state.update { it.copy(sort = sort) }
    }

    /** Step back from the item grid to the folder picker (in-picker back navigation). */
    fun clearAlbum() {
        _state.update {
            it.copy(selectedAlbumId = null, sources = emptyList(), selectedIds = emptySet(), previewItemId = null)
        }
    }

    fun hideNow() {
        val current = _state.value
        if (!current.hideEnabled) return
        _state.update { it.copy(hiding = true) }
        viewModelScope.launch {
            val chosen =
                if (current.selectedAlbumId == null) {
                    folderSelectionSources(current)
                } else {
                    current.sources.filter { it.id in current.selectedIds }
                }
            if (chosen.isEmpty()) {
                // Every staged folder turned out empty — nothing to hide, just disarm.
                _state.update { it.copy(hiding = false, selectedFolderIds = emptySet()) }
                return@launch
            }
            // S16: each item lands in a vault folder named after its source device bucket
            // (Camera → "Camera"…), found or created per distinct bucket, so the category
            // screen's folder tiles mirror the source albums. Items browsed via the Recent
            // aggregate carry their *real* bucket name (MediaSource keeps it per row).
            val folderIdByBucket =
                folderIdsForBuckets(chosen.mapNotNull { src -> src.albumName.takeIf { it.isNotBlank() } }.toSet())
            repository.hide(chosen.map { src -> src.toVaultItem(category, folderIdByBucket[src.albumName]) })
            val deletable = chosen.mapNotNull { it.contentUri.takeIf { uri -> uri.isNotBlank() } }
            when {
                deletable.isEmpty() ->
                    _state.update {
                        it.copy(hiding = false, done = true, selectedIds = emptySet(), selectedFolderIds = emptySet())
                    }
                // Contacts have no MediaStore delete-consent dialog. Contacts access was
                // dropped per board scope refinement (APP-207), so deleteContacts is now a
                // no-op (returns 0) — kept for shape until the Contacts category is retired.
                category == VaultCategory.CONTACTS -> {
                    withContext(Dispatchers.IO) { mediaSource?.deleteContacts(deletable) }
                    _state.update {
                        it.copy(hiding = false, done = true, selectedIds = emptySet(), selectedFolderIds = emptySet())
                    }
                }
                // Media: hand the originals to the screen's MediaStore delete-request.
                else ->
                    _state.update {
                        it.copy(
                            hiding = false,
                            selectedIds = emptySet(),
                            selectedFolderIds = emptySet(),
                            pendingDeleteUris = deletable,
                        )
                    }
            }
        }
    }

    /** The screen calls this once the public originals' delete request resolves. */
    fun onOriginalsRemoved() {
        _state.update { it.copy(pendingDeleteUris = emptyList(), done = true) }
    }

    /**
     * Enumerate the items behind every folder staged on S14, deduped by id — the same
     * original can surface through several selected folders, and hiding it twice would
     * double-encrypt and double-delete.
     */
    private suspend fun folderSelectionSources(current: HideImportState): List<SourceItem> {
        val stagedIds = current.albums.map { it.id }.filter { it in current.selectedFolderIds }
        val all =
            stagedIds.flatMap { albumId ->
                if (mediaSource != null && current.permissionGranted) {
                    withContext(Dispatchers.IO) { mediaSource.sources(category, albumId) }
                } else {
                    sampleSources(category, albumId)
                }
            }
        return all.distinctBy { it.id }
    }

    /**
     * Resolve each distinct source-bucket name to a vault folder id in this category:
     * reuse an existing folder with the same name (case-insensitive, so the seeded
     * "Download" folder absorbs MediaStore's "Download" bucket) or create it.
     */
    private suspend fun folderIdsForBuckets(bucketNames: Set<String>): Map<String, String> {
        if (bucketNames.isEmpty()) return emptyMap()
        val existing = repository.folders(category).first()
        val out = mutableMapOf<String, String>()
        for (name in bucketNames) {
            val match = existing.firstOrNull { it.name.equals(name, ignoreCase = true) }
            out[name] = match?.id ?: repository.createFolder(category, name).id
        }
        return out
    }

    private fun SourceItem.toVaultItem(
        category: VaultCategory,
        folderId: String?,
    ): VaultItem =
        VaultItem(
            id = id,
            category = category,
            originalName = name,
            dateLabel = dateLabel,
            sortKey = sortKey,
            folderId = folderId,
            sourceUri = contentUri.takeIf { it.isNotBlank() },
            mimeType = mimeType,
            relativePath = relativePath.takeIf { it.isNotBlank() },
        )

    private companion object {
        fun initialState(
            category: VaultCategory,
            mediaSource: MediaSource?,
        ): HideImportState =
            HideImportState(
                category = category,
                // Real albums load after the permission grant; preview/tests use samples.
                albums = if (mediaSource == null) sampleAlbums(category) else emptyList(),
            )

        fun sampleAlbums(category: VaultCategory): List<SourceAlbum> {
            val buckets =
                when (category) {
                    VaultCategory.PHOTOS ->
                        listOf(
                            SourceAlbum("camera", "Camera", 248),
                            SourceAlbum("screenshots", "Screenshots", 61),
                            SourceAlbum("downloads", "Download", 12),
                        )
                    VaultCategory.VIDEOS ->
                        listOf(SourceAlbum("camera", "Camera", 34), SourceAlbum("downloads", "Download", 5))
                    VaultCategory.AUDIOS ->
                        listOf(SourceAlbum("recordings", "Recordings", 18), SourceAlbum("music", "Music", 92))
                    VaultCategory.FILES ->
                        listOf(SourceAlbum("downloads", "Download", 40), SourceAlbum("documents", "Documents", 23))
                    VaultCategory.CONTACTS ->
                        return listOf(SourceAlbum("phone", "Phone contacts", 214))
                }
            // Lead with the "Recent" aggregate (xlock's default public / All-Files folder).
            val recent = SourceAlbum(SourceAlbum.RECENT_ID, "Recent", buckets.sumOf { it.count })
            return listOf(recent) + buckets
        }

        fun sampleSources(
            category: VaultCategory,
            albumId: String,
        ): List<SourceItem> {
            val day = 24L * 60L * 60L * 1000L
            val base = 100L * day
            val isRecent = albumId == SourceAlbum.RECENT_ID
            val albums = sampleAlbums(category)
            val album = albums.firstOrNull { it.id == albumId }
            // The Recent aggregate mixes every real bucket, so its sample items cycle
            // through the real buckets — mirroring MediaSource, which keeps each row's
            // true bucket even in the unfiltered query (the S16 folder mapping relies
            // on that: Recent picks land in their real bucket's vault folder).
            val realBuckets = albums.filterNot { it.id == SourceAlbum.RECENT_ID }
            val ext =
                when (category) {
                    VaultCategory.PHOTOS -> "jpg"
                    VaultCategory.VIDEOS -> "mp4"
                    VaultCategory.AUDIOS -> "m4a"
                    VaultCategory.FILES -> "pdf"
                    VaultCategory.CONTACTS -> ""
                }
            return (0 until 9).map { i ->
                val ageDays = i / 3L // three per date section
                val label =
                    when (ageDays) {
                        0L -> "Today"
                        1L -> "Yesterday"
                        else -> "${12 - ageDays} Jun 2026"
                    }
                val bucket = if (isRecent && realBuckets.isNotEmpty()) realBuckets[i % realBuckets.size] else album
                val bucketName = bucket?.name ?: albumId
                val prefix = (if (isRecent) "IMG" else bucketName.take(3)).uppercase()
                val name = if (ext.isEmpty()) "Contact ${i + 1}" else "${prefix}_${1000 + i}.$ext"
                SourceItem(
                    id = "$albumId-$i",
                    name = name,
                    dateLabel = label,
                    sortKey = base - ageDays * day - i,
                    albumId = bucket?.id ?: albumId,
                    albumName = bucketName,
                    sizeBytes = (i + 1) * 512L * 1024L,
                    dateModified = base - i * 3_600_000L,
                )
            }
        }
    }
}
