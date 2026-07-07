package com.appblish.calculatorvault.vault.actions

import com.appblish.calculatorvault.vault.model.UnhideResult

/**
 * Turns an [UnhideResult] into the design's §7 result copy — the honest "never fail
 * silently" snackbar. Pure (no Android deps) so the exact strings are JVM-unit-testable:
 *
 *  - all landed at the requested dest → "Unhid N photo(s)."
 *  - some fell back                   → "Unhid N · saved M to {dest} (original unavailable)."
 *  - all fell back                    → "Original folder unavailable — saved N to {dest}."
 *  - nothing writable                 → "Couldn't unhide — check storage access."
 */
object UnhideMessages {
    fun summary(result: UnhideResult): String {
        val dest = result.fallbackDestination ?: "Downloads"
        return when {
            result.outcomes.isEmpty() -> "Nothing to unhide."
            result.totalFailure -> "Couldn't unhide — check storage access."
            result.fellBack > 0 && result.requested == 0 ->
                "Original folder unavailable — saved ${result.fellBack} to $dest."
            result.fellBack > 0 ->
                "Unhid ${result.requested} · saved ${result.fellBack} to $dest (original unavailable)."
            else -> "Unhid ${result.requested} ${photos(result.requested)}."
        }
    }

    private fun photos(n: Int): String = if (n == 1) "photo" else "photos"
}
