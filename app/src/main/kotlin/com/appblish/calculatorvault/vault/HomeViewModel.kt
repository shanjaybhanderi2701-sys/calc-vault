package com.appblish.calculatorvault.vault

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home-dashboard state: per-category item + folder counts (the deck's dual-count tile
 * subtitles), the recycle-bin size (the Bin grid tile), and the cross-category "Recent"
 * strip. [isEmpty] backs the first-run coach-mark — true only when nothing is hidden yet.
 */
data class VaultHomeState(
    val counts: Map<VaultCategory, Int> = VaultCategory.entries.associateWith { 0 },
    val folderCounts: Map<VaultCategory, Int> = VaultCategory.entries.associateWith { 0 },
    val binCount: Int = 0,
    val recent: List<VaultItem> = emptyList(),
) {
    // "Empty" == the user has hidden nothing yet. Deliberately ignores folderCounts:
    // every fresh vault seeds a "Download" folder per category (APP-206/APP-220), so an
    // emptiness signal that counted folders would never be true. Note the first-run hint
    // itself is NOT gated on this — it is APP-236's pref-gated tooltip overlay (APP-235).
    val isEmpty: Boolean
        get() = recent.isEmpty() && counts.values.all { it == 0 }
}

/**
 * Drives the vault-home dashboard. Combines the repository's category counts, folder
 * counts, recycle-bin size, and recent items into one observable state; purges any
 * expired recycle-bin entries on open so the auto-delete window is enforced whenever the
 * user returns to the vault.
 */
class HomeViewModel(
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val state: StateFlow<VaultHomeState> =
        combine(
            repository.categoryCounts(),
            repository.folderCounts(),
            repository.recycleBin(),
            repository.recent(),
        ) { counts, folderCounts, bin, recent ->
            VaultHomeState(
                counts = counts,
                folderCounts = folderCounts,
                binCount = bin.size,
                recent = recent,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultHomeState())

    init {
        // Enforce the recycle-bin auto-delete window whenever the vault is opened.
        viewModelScope.launch { repository.purgeExpired(clock()) }
    }

    /**
     * Load a cover thumbnail for a Recent-strip [item] through [VaultThumbnailPipeline]
     * (APP-244) — the same cached path the category grids use, so the home strip shows
     * real covers instantly on revisits without re-decrypting (APP-234 spec §2.3).
     */
    suspend fun thumbnail(
        context: Context,
        item: VaultItem,
    ): ImageBitmap? = VaultThumbnailPipeline.load(context, item, repository)
}
