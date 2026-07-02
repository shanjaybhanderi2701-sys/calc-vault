package com.appblish.calculatorvault.explore.junk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One reclaimable bucket the scan surfaces. Sizes are in bytes. */
data class JunkCategory(
    val key: String,
    val label: String,
    val bytes: Long,
    val selected: Boolean = true,
)

/** Where the cleaner is in its scan → clean lifecycle. */
enum class JunkPhase { IDLE, SCANNING, RESULTS, CLEANING, DONE }

data class JunkCleanerState(
    val phase: JunkPhase = JunkPhase.IDLE,
    val categories: List<JunkCategory> = emptyList(),
    val freedBytes: Long = 0L,
) {
    val selectedBytes: Long get() = categories.filter { it.selected }.sumOf { it.bytes }
    val totalBytes: Long get() = categories.sumOf { it.bytes }
    val canClean: Boolean get() = phase == JunkPhase.RESULTS && selectedBytes > 0
}

/**
 * Backs the Junk Cleaner. A scan sweeps the app-owned caches the vault can legitimately
 * reclaim (its own thumbnail/decrypt scratch, temp import files, empty folders) and reports
 * per-bucket sizes; cleaning the selected buckets zeroes them and reports the total freed.
 *
 * Phase 4 models the flow deterministically so it is demonstrable and testable; wiring the
 * scan to real `cacheDir`/`externalCacheDir` traversal is a thin swap behind [scan]/[clean].
 */
class JunkCleanerViewModel(
    private val scanner: () -> List<JunkCategory> = ::defaultBuckets,
) : ViewModel() {
    private val _state = MutableStateFlow(JunkCleanerState())
    val state: StateFlow<JunkCleanerState> = _state.asStateFlow()

    fun scan() {
        if (_state.value.phase == JunkPhase.SCANNING || _state.value.phase == JunkPhase.CLEANING) return
        _state.value = JunkCleanerState(phase = JunkPhase.SCANNING)
        viewModelScope.launch {
            delay(SCAN_MILLIS)
            _state.value = JunkCleanerState(phase = JunkPhase.RESULTS, categories = scanner())
        }
    }

    fun toggle(key: String) {
        if (_state.value.phase != JunkPhase.RESULTS) return
        _state.value =
            _state.value.copy(
                categories = _state.value.categories.map {
                    if (it.key ==
                        key
                    ) {
                        it.copy(selected = !it.selected)
                    } else {
                        it
                    }
                },
            )
    }

    fun clean() {
        val current = _state.value
        if (!current.canClean) return
        val freed = current.selectedBytes
        _state.value = current.copy(phase = JunkPhase.CLEANING)
        viewModelScope.launch {
            delay(CLEAN_MILLIS)
            _state.value = JunkCleanerState(phase = JunkPhase.DONE, freedBytes = freed)
        }
    }

    fun reset() {
        _state.value = JunkCleanerState()
    }

    companion object {
        const val SCAN_MILLIS = 1_400L
        const val CLEAN_MILLIS = 900L

        private fun defaultBuckets(): List<JunkCategory> =
            listOf(
                JunkCategory("thumb", "Thumbnail cache", 34L * 1024 * 1024),
                JunkCategory("decrypt", "Decrypt scratch files", 12L * 1024 * 1024),
                JunkCategory("temp", "Temporary import files", 6L * 1024 * 1024),
                JunkCategory("empty", "Empty folders", 0L),
            )
    }
}
