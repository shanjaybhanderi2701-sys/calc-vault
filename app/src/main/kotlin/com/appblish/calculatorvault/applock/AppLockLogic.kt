package com.appblish.calculatorvault.applock

/**
 * Pure AppLock decision logic, factored out of the Compose/Android layers so the
 * enforcement rules — list filtering, re-lock timing, intruder threshold — are unit-tested
 * without an emulator. The [AppLockAccessibilityService] and [ui] screens call these; they
 * hold no Android types on purpose.
 */
object AppLockLogic {
    /** Apply the All/Unlocked/Locked segmented filter to the app list. */
    fun filter(
        apps: List<InstalledApp>,
        filter: AppLockFilter,
    ): List<InstalledApp> =
        when (filter) {
            AppLockFilter.All -> apps
            AppLockFilter.Locked -> apps.filter { it.locked }
            AppLockFilter.Unlocked -> apps.filter { !it.locked }
        }

    /** Case-insensitive label/package search over the list (empty query = unchanged). */
    fun search(
        apps: List<InstalledApp>,
        query: String,
    ): List<InstalledApp> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        return apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    /**
     * Whether the current run of wrong attempts has reached the intruder-capture threshold.
     * Capture fires exactly on the threshold-th failure (not every failure after) so one
     * break-in attempt logs one selfie.
     */
    fun shouldCaptureIntruder(
        wrongAttempts: Int,
        settings: AppLockSettings,
    ): Boolean = settings.intruderEnabled && wrongAttempts == settings.intruderThreshold
}
