package com.appblish.calculatorvault.vault.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.VaultContentRepository
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Resolves a single [VaultItem] by id for the viewer, and routes its Delete action to
 * the recycle bin. Observes the shared repository so a delete elsewhere closes the
 * viewer (item goes null). Also decrypts the item's blob once so the viewer renders the
 * real bytes (image/video/audio/contact) rather than a placeholder.
 */
class ViewerViewModel(
    private val itemId: String,
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
) : ViewModel() {
    val item: StateFlow<VaultItem?> =
        repository
            .allItems()
            .map { list -> list.firstOrNull { it.id == itemId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _decrypted = MutableStateFlow<ByteArray?>(null)

    /** Decrypted blob bytes for [itemId]; null while loading or if the blob is missing. */
    val decrypted: StateFlow<ByteArray?> = _decrypted.asStateFlow()

    init {
        viewModelScope.launch { _decrypted.value = repository.openDecrypted(itemId) }
    }

    fun delete() {
        viewModelScope.launch { repository.moveToRecycleBin(setOf(itemId)) }
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
