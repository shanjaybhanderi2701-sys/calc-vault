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

/** Hide/import flow state: folder picker → date-grouped multi-select → Hide Now. */
data class HideImportState(
    val category: VaultCategory,
    val albums: List<SourceAlbum> = emptyList(),
    val selectedAlbumId: String? = null,
    val sources: List<SourceItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
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
    val hideEnabled: Boolean get() = selectedIds.isNotEmpty() && !hiding

    /** The currently-open album, if any (used for the item-grid header subtitle). */
    val selectedAlbum: SourceAlbum? get() = albums.firstOrNull { it.id == selectedAlbumId }
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
 * Drives the hide/import flow shared by all five categories. On device it loads the real
 * public-storage candidates from [MediaSource] (MediaStore / ContactsContract) once the
 * runtime permission is granted; without a [MediaSource] (Compose preview / tests) it
 * shows deterministic sample data. The user picks a folder (or the "Recent"/All-Files
 * aggregate), multi-selects date-grouped items, and on **Hide Now** the chosen items are
 * handed to [VaultContentRepository.hide], which streams each original through
 * `VaultCrypto` into an encrypted blob. The public originals are then removed via the
 * screen's delete request (see [pendingDeleteUris]).
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
            _state.update { it.copy(selectedAlbumId = albumId, sources = sources, selectedIds = emptySet()) }
        }
    }

    fun toggle(itemId: String) {
        _state.update { current ->
            val ids = if (itemId in current.selectedIds) current.selectedIds - itemId else current.selectedIds + itemId
            current.copy(selectedIds = ids)
        }
    }

    /** Select-all / clear for the visible album (the header "pinch-all" affordance). */
    fun toggleAll() {
        _state.update { current ->
            val all = current.sources.map { it.id }.toSet()
            current.copy(selectedIds = if (current.selectedIds.containsAll(all)) emptySet() else all)
        }
    }

    /** Change the item-grid sort order (xlock parity sort menu). */
    fun setSort(sort: PickerSort) {
        _state.update { it.copy(sort = sort) }
    }

    /** Step back from the item grid to the folder picker (in-picker back navigation). */
    fun clearAlbum() {
        _state.update { it.copy(selectedAlbumId = null, sources = emptyList(), selectedIds = emptySet()) }
    }

    fun hideNow() {
        val current = _state.value
        if (!current.hideEnabled) return
        val chosen = current.sources.filter { it.id in current.selectedIds }
        _state.update { it.copy(hiding = true) }
        viewModelScope.launch {
            repository.hide(chosen.map { src -> src.toVaultItem(category) })
            val deletable = chosen.mapNotNull { it.contentUri.takeIf { uri -> uri.isNotBlank() } }
            when {
                deletable.isEmpty() ->
                    _state.update { it.copy(hiding = false, done = true, selectedIds = emptySet()) }
                // Contacts have no MediaStore delete-consent dialog. Contacts access was
                // dropped per board scope refinement (APP-207), so deleteContacts is now a
                // no-op (returns 0) — kept for shape until the Contacts category is retired.
                category == VaultCategory.CONTACTS -> {
                    withContext(Dispatchers.IO) { mediaSource?.deleteContacts(deletable) }
                    _state.update { it.copy(hiding = false, done = true, selectedIds = emptySet()) }
                }
                // Media: hand the originals to the screen's MediaStore delete-request.
                else ->
                    _state.update {
                        it.copy(hiding = false, selectedIds = emptySet(), pendingDeleteUris = deletable)
                    }
            }
        }
    }

    /** The screen calls this once the public originals' delete request resolves. */
    fun onOriginalsRemoved() {
        _state.update { it.copy(pendingDeleteUris = emptyList(), done = true) }
    }

    private fun SourceItem.toVaultItem(category: VaultCategory): VaultItem =
        VaultItem(
            id = id,
            category = category,
            originalName = name,
            dateLabel = dateLabel,
            sortKey = sortKey,
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
            val album = sampleAlbums(category).firstOrNull { it.id == albumId }
            val albumName = album?.name ?: albumId
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
                val prefix = (if (isRecent) "IMG" else albumName.take(3)).uppercase()
                val name = if (ext.isEmpty()) "Contact ${i + 1}" else "${prefix}_${1000 + i}.$ext"
                SourceItem(
                    id = "$albumId-$i",
                    name = name,
                    dateLabel = label,
                    sortKey = base - ageDays * day - i,
                    albumId = albumId,
                    albumName = albumName,
                    sizeBytes = (i + 1) * 512L * 1024L,
                    dateModified = base - i * 3_600_000L,
                )
            }
        }
    }
}
