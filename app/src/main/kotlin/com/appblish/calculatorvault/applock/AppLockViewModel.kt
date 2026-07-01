package com.appblish.calculatorvault.applock

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.intruder.IntruderEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable state the AppLock tab renders. */
data class AppLockUiState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val filter: AppLockFilter = AppLockFilter.All,
    val query: String = "",
    val settings: AppLockSettings = AppLockSettings(),
    val hasSeenSuggestions: Boolean = true,
    val intruderEvents: List<IntruderEvent> = emptyList(),
) {
    val lockedCount: Int get() = apps.count { it.locked }

    /** The list after the All/Unlocked/Locked filter and the search query. */
    val visibleApps: List<InstalledApp>
        get() = AppLockLogic.search(AppLockLogic.filter(apps, filter), query)

    /** Unlocked, board-suggested apps — the pre-checked first-run "Suggested" section. */
    val suggestedUnlocked: List<InstalledApp>
        get() = apps.filter { it.suggested && !it.locked }
}

/**
 * Drives the AppLock tab: loads the device app list, applies the All/Unlocked/Locked filter
 * and search, toggles per-app locks through the [AppLockStore], and exposes the tunable
 * [AppLockSettings] and the intruder log. Constructed with an app [Context] so it can reach
 * the [AppInventory]; stores come from [AppLockGraph] so the enforcement service sees the
 * same state.
 */
class AppLockViewModel(
    context: Context,
) : ViewModel() {
    private val inventory = AppInventory(context.applicationContext)
    private val lockStore = AppLockGraph.appLockStore
    private val intruderStore = AppLockGraph.intruderLogStore

    private val _state = MutableStateFlow(AppLockUiState())
    val state: StateFlow<AppLockUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val locked = lockStore.lockedPackages()
            val apps = inventory.installedApps(locked)
            val settings = lockStore.settings()
            val seen = lockStore.hasSeenSuggestions()
            val events = intruderStore.events()
            _state.update {
                it.copy(
                    loading = false,
                    apps = apps,
                    settings = settings,
                    hasSeenSuggestions = seen,
                    intruderEvents = events,
                )
            }
        }
    }

    fun setFilter(filter: AppLockFilter) = _state.update { it.copy(filter = filter) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setLocked(
        packageName: String,
        locked: Boolean,
    ) {
        // Optimistic UI; persist so the enforcement service picks it up on its next refresh.
        _state.update { s ->
            s.copy(apps = s.apps.map { if (it.packageName == packageName) it.copy(locked = locked) else it })
        }
        viewModelScope.launch { lockStore.setLocked(packageName, locked) }
    }

    /** Lock every package in [packageNames] (the picker's "LOCK (n)" and "Lock all suggested"). */
    fun lockAll(packageNames: Collection<String>) {
        if (packageNames.isEmpty()) return
        val set = packageNames.toSet()
        _state.update { s ->
            s.copy(apps = s.apps.map { if (it.packageName in set) it.copy(locked = true) else it })
        }
        viewModelScope.launch { lockStore.lockAll(set) }
    }

    fun dismissSuggestions() {
        _state.update { it.copy(hasSeenSuggestions = true) }
        viewModelScope.launch { lockStore.markSuggestionsSeen() }
    }

    fun updateSettings(settings: AppLockSettings) {
        _state.update { it.copy(settings = settings) }
        viewModelScope.launch { lockStore.setSettings(settings) }
    }

    fun clearIntruderLog() {
        _state.update { it.copy(intruderEvents = emptyList()) }
        viewModelScope.launch { intruderStore.clear() }
    }
}
