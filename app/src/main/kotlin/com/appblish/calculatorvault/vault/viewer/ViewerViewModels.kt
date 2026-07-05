package com.appblish.calculatorvault.vault.viewer

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.VaultContentRepository
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Resolves a single [VaultItem] by id for the viewer and decrypts its blob for display.
 * Observes the shared repository so a delete elsewhere closes the viewer (item goes null).
 *
 * Decryption routing (spec §7): images/contacts stay purely in memory ([decrypted]);
 * video/audio are decrypted to a **temp file in the app-private cache dir** ([mediaFile])
 * so ExoPlayer can stream them — decrypted bytes never touch public storage in cleartext.
 * The temp file has no extension and a random name, and is deleted when the player screen
 * disposes it, with [onCleared] as a backstop.
 *
 * Delete routes through the shared delete-choice modal (design call D-4): the screen asks,
 * this VM executes either [moveToRecycleBin] or [deletePermanently]. Restore (spec §8
 * vocabulary) uses [restore], which surfaces the per-operation outcome via a Toast
 * (design call D-3 allows this surface for the single-item viewer path).
 */
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

    private val _decrypted = MutableStateFlow<ByteArray?>(null)

    /** Decrypted blob bytes (images/contacts); null while loading or if the blob is missing. */
    val decrypted: StateFlow<ByteArray?> = _decrypted.asStateFlow()

    private val _mediaFile = MutableStateFlow<File?>(null)

    /** Private-cache temp file holding the decrypted video/audio blob; null for other kinds. */
    val mediaFile: StateFlow<File?> = _mediaFile.asStateFlow()

    // Recorded before the first byte is written so onCleared() can always clean up,
    // even if the write is interrupted mid-flight.
    private var tempFile: File? = null

    init {
        viewModelScope.launch {
            // Wait for the item so we know its category before choosing the decrypt route.
            val current = item.filterNotNull().first()
            when (current.category) {
                VaultCategory.VIDEOS, VaultCategory.AUDIOS -> {
                    val cache = appContext?.cacheDir ?: return@launch
                    val target = File(File(cache, VIEWER_CACHE_DIR), UUID.randomUUID().toString())
                    tempFile = target
                    // Stream blob → cipher → file so a large video is never held in memory
                    // (spec §11); decryptToFile deletes any partial file on failure.
                    val ok =
                        withContext(Dispatchers.IO) {
                            target.parentFile?.mkdirs()
                            repository.decryptToFile(itemId, target)
                        }
                    if (ok) _mediaFile.value = target
                }
                else -> {
                    _decrypted.value = withContext(Dispatchers.IO) { repository.openDecrypted(itemId) }
                }
            }
        }
    }

    /** Send this item to the recycle bin (the safe default of the delete-choice modal). */
    fun moveToRecycleBin() {
        viewModelScope.launch {
            // NonCancellable: the caller pops back immediately, which clears this VM —
            // the mutation must still complete.
            withContext(NonCancellable) { repository.moveToRecycleBin(setOf(itemId)) }
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

    // Backstop cleanup: the screen deletes the temp file on dispose, but a process-level
    // VM clear (e.g. viewer never composed the player) must not strand cleartext in cache.
    override fun onCleared() {
        tempFile?.delete()
        super.onCleared()
    }

    private companion object {
        const val VIEWER_CACHE_DIR = "viewer"
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
