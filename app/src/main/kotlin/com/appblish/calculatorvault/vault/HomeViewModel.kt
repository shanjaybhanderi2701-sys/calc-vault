package com.appblish.calculatorvault.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val isEmpty: Boolean
        get() = recent.isEmpty() && counts.values.all { it == 0 } && folderCounts.values.all { it == 0 }
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
}
