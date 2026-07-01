package com.appblish.calculatorvault.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A pickable source item from public storage, staged for hiding. */
data class SourceItem(
    val id: String,
    val name: String,
    val dateLabel: String,
    val sortKey: Long,
    val albumId: String,
    val albumName: String,
)

/** Hide/import flow state: album picker → date-grouped multi-select → Hide Now. */
data class HideImportState(
    val category: VaultCategory,
    val albums: List<SourceAlbum> = emptyList(),
    val selectedAlbumId: String? = null,
    val sources: List<SourceItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val hiding: Boolean = false,
    val done: Boolean = false,
) {
    val hideEnabled: Boolean get() = selectedIds.isNotEmpty() && !hiding
}

/** One album/bucket in the picker (Camera, Screenshots, Downloads…). */
data class SourceAlbum(
    val id: String,
    val name: String,
    val count: Int,
)

/**
 * Drives the hide/import flow shared by all five categories. Loads the public-storage
 * source candidates (from MediaStore/SAF on device — sample data here), lets the user
 * pick an album then multi-select date-grouped items, and on **Hide Now** stages the
 * chosen items and hands them to [VaultContentRepository.hide], which encrypts each and
 * removes the public original.
 */
class HideImportViewModel(
    val category: VaultCategory,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState(category))
    val state: StateFlow<HideImportState> = _state.asStateFlow()

    fun selectAlbum(albumId: String) {
        _state.update { current ->
            current.copy(
                selectedAlbumId = albumId,
                sources = sampleSources(category, albumId),
                selectedIds = emptySet(),
            )
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

    fun hideNow() {
        val current = _state.value
        if (!current.hideEnabled) return
        val chosen = current.sources.filter { it.id in current.selectedIds }
        _state.update { it.copy(hiding = true) }
        viewModelScope.launch {
            repository.hide(chosen.map { src -> src.toVaultItem(category) })
            _state.update { it.copy(hiding = false, done = true, selectedIds = emptySet()) }
        }
    }

    private fun SourceItem.toVaultItem(category: VaultCategory): VaultItem =
        VaultItem(
            id = id,
            category = category,
            originalName = name,
            dateLabel = dateLabel,
            sortKey = sortKey,
        )

    private companion object {
        fun initialState(category: VaultCategory): HideImportState =
            HideImportState(category = category, albums = sampleAlbums(category))

        fun sampleAlbums(category: VaultCategory): List<SourceAlbum> =
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
                    listOf(SourceAlbum("phone", "Phone contacts", 214))
            }

        fun sampleSources(
            category: VaultCategory,
            albumId: String,
        ): List<SourceItem> {
            val day = 24L * 60L * 60L * 1000L
            val base = 100L * day
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
                val prefix = albumName.take(3).uppercase()
                val name = if (ext.isEmpty()) "Contact ${i + 1}" else "${prefix}_${1000 + i}.$ext"
                SourceItem(
                    id = "$albumId-$i",
                    name = name,
                    dateLabel = label,
                    sortKey = base - ageDays * day - i,
                    albumId = albumId,
                    albumName = albumName,
                )
            }
        }
    }
}
