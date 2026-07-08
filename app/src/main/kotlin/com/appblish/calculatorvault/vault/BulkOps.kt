package com.appblish.calculatorvault.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped executor for the grid's bulk operations (W1-E3, spec §1.6). A bulk
 * move/unhide/delete must survive the user navigating away mid-batch: the repository keeps
 * the *process* alive under [com.appblish.calculatorvault.vault.media.BulkOpService]'s
 * foreground notification, and this scope keeps the *coroutine* alive when the launching
 * screen's ViewModel is cleared — `viewModelScope` would cancel the batch half-done.
 *
 * Each op returns its honest one-line summary ("Unhid 3 · saved 1 to Downloads…"),
 * published on [summary] for whichever category screen is active — or comes back — to
 * show as the one-per-operation snackbar (D-3, never silent). The slot is process-wide
 * like [HideImportViewModel.hideSummary], and is consumed by the screen the same way.
 */
object BulkOps {
    // Swapped for the test scheduler's scope in JVM tests; SupervisorJob so one failed
    // batch never cancels the executor for subsequent ones.
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val notice = MutableStateFlow<String?>(null)

    /** The pending bulk-op summary, or null. Cleared by [consume] once shown. */
    val summary: StateFlow<String?> = notice.asStateFlow()

    /**
     * Run [block] detached from any ViewModel and publish the summary it returns. A crash
     * inside the batch still surfaces a notice — a bulk op must never fail silently
     * (spec §1.4), even on an unexpected exception.
     */
    fun run(block: suspend () -> String?) {
        scope.launch {
            notice.value =
                runCatching { block() }
                    .getOrElse { "Couldn't finish the operation (${it.javaClass.simpleName})" }
        }
    }

    /** The active screen consumed the summary snackbar. */
    fun consume() {
        notice.value = null
    }
}
