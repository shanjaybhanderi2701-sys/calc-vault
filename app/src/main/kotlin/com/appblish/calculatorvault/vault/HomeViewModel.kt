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

/** Home-dashboard state: per-category counts and the cross-category "Recent" strip. */
data class VaultHomeState(
    val counts: Map<VaultCategory, Int> = VaultCategory.entries.associateWith { 0 },
    val recent: List<VaultItem> = emptyList(),
)

/**
 * Drives the vault-home dashboard. Combines the repository's category counts and recent
 * items into one observable state; purges any expired recycle-bin entries on open so the
 * auto-delete window is enforced whenever the user returns to the vault.
 */
class HomeViewModel(
    private val repository: VaultContentRepository = VaultGraph.contentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val state: StateFlow<VaultHomeState> =
        combine(repository.categoryCounts(), repository.recent()) { counts, recent ->
            VaultHomeState(counts = counts, recent = recent)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultHomeState())

    init {
        // Enforce the recycle-bin auto-delete window whenever the vault is opened.
        viewModelScope.launch { repository.purgeExpired(clock()) }
    }
}
