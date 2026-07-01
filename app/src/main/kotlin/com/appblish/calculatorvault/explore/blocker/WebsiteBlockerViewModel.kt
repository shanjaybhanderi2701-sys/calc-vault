package com.appblish.calculatorvault.explore.blocker

import androidx.lifecycle.ViewModel
import com.appblish.calculatorvault.explore.BlockedSite
import com.appblish.calculatorvault.explore.ExploreStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs the Website Blocker list. Reads and mutates the shared [ExploreStore] blocklist so
 * the Private Browser (which consults the same store) immediately honors changes.
 */
class WebsiteBlockerViewModel : ViewModel() {
    val sites: StateFlow<List<BlockedSite>> = ExploreStore.blockedSites

    /** Returns false when the entry is invalid or already blocked (caller shows a hint). */
    fun add(raw: String): Boolean = ExploreStore.addBlockedSite(raw)

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = ExploreStore.setBlockedEnabled(id, enabled)

    fun remove(id: String) = ExploreStore.removeBlockedSite(id)
}
