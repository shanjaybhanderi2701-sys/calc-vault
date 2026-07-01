package com.appblish.calculatorvault.applock

/**
 * Persistence seam for AppLock: which packages are locked, the tunable [AppLockSettings],
 * and the per-package "last correct unlock" timestamps that drive re-lock timing. Mirrors
 * the auth layer's [com.appblish.calculatorvault.auth.CredentialStore] split — an interface
 * plus a [BaseAppLockStore] that holds all the encoding logic on top of three string
 * primitives, so the encrypted device store and the in-memory test store stay identical in
 * behaviour. All methods are `suspend`: the encrypted prefs open and write off the main
 * thread.
 */
interface AppLockStore {
    /** Packages the owner has chosen to lock. */
    suspend fun lockedPackages(): Set<String>

    /** True if [packageName] is currently locked. */
    suspend fun isLocked(packageName: String): Boolean

    /** Lock or unlock [packageName]. */
    suspend fun setLocked(
        packageName: String,
        locked: Boolean,
    )

    /** Lock every package in [packageNames] in one write (the picker's "LOCK (n)"). */
    suspend fun lockAll(packageNames: Collection<String>)

    /** The tunable settings (method, re-lock delay, intruder). */
    suspend fun settings(): AppLockSettings

    /** Replace the settings. */
    suspend fun setSettings(settings: AppLockSettings)

    /** Record that [packageName] was just unlocked at [atMs] (starts its grace window). */
    suspend fun recordUnlock(
        packageName: String,
        atMs: Long,
    )

    /** The last correct-unlock timestamp for [packageName], or null if never/again-locked. */
    suspend fun lastUnlockAt(packageName: String): Long?

    /** Whether AppLock's first-run suggested set has already been offered once. */
    suspend fun hasSeenSuggestions(): Boolean

    /** Mark the first-run suggested sheet as shown so it never nags again. */
    suspend fun markSuggestionsSeen()

    /** Wipe all AppLock state (full reset). */
    suspend fun clearAll()
}

/**
 * Shared [AppLockStore] behaviour on three primitives ([getValue]/[setValue]/[removeValue])
 * plus [clearValues]. Concrete stores implement only raw string persistence.
 */
abstract class BaseAppLockStore : AppLockStore {
    protected abstract suspend fun getValue(key: String): String?

    protected abstract suspend fun setValue(
        key: String,
        value: String,
    )

    protected abstract suspend fun removeValue(key: String)

    protected abstract suspend fun clearValues()

    override suspend fun lockedPackages(): Set<String> =
        getValue(KEY_LOCKED)
            ?.split(DELIM)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    override suspend fun isLocked(packageName: String): Boolean = packageName in lockedPackages()

    override suspend fun setLocked(
        packageName: String,
        locked: Boolean,
    ) {
        val current = lockedPackages().toMutableSet()
        val changed = if (locked) current.add(packageName) else current.remove(packageName)
        if (!changed) return
        persistLocked(current)
        if (!locked) removeValue(unlockKey(packageName))
    }

    override suspend fun lockAll(packageNames: Collection<String>) {
        if (packageNames.isEmpty()) return
        val current = lockedPackages().toMutableSet()
        current.addAll(packageNames)
        persistLocked(current)
    }

    private suspend fun persistLocked(packages: Set<String>) {
        if (packages.isEmpty()) removeValue(KEY_LOCKED) else setValue(KEY_LOCKED, packages.joinToString(DELIM))
    }

    override suspend fun settings(): AppLockSettings =
        AppLockSettings(
            lockMethod =
                getValue(KEY_METHOD)?.let { runCatching { LockMethod.valueOf(it) }.getOrNull() } ?: LockMethod.Pin,
            relockDelayMs = getValue(KEY_RELOCK)?.toLongOrNull() ?: 0L,
            intruderEnabled = getValue(KEY_INTRUDER_ON) == "true",
            intruderThreshold =
                getValue(KEY_INTRUDER_N)?.toIntOrNull()?.coerceIn(
                    AppLockSettings.MIN_THRESHOLD,
                    AppLockSettings.MAX_THRESHOLD,
                ) ?: 3,
        )

    override suspend fun setSettings(settings: AppLockSettings) {
        setValue(KEY_METHOD, settings.lockMethod.name)
        setValue(KEY_RELOCK, settings.relockDelayMs.toString())
        setValue(KEY_INTRUDER_ON, settings.intruderEnabled.toString())
        setValue(
            KEY_INTRUDER_N,
            settings.intruderThreshold
                .coerceIn(AppLockSettings.MIN_THRESHOLD, AppLockSettings.MAX_THRESHOLD)
                .toString(),
        )
    }

    override suspend fun recordUnlock(
        packageName: String,
        atMs: Long,
    ) = setValue(unlockKey(packageName), atMs.toString())

    override suspend fun lastUnlockAt(packageName: String): Long? = getValue(unlockKey(packageName))?.toLongOrNull()

    override suspend fun hasSeenSuggestions(): Boolean = getValue(KEY_SUGGESTED_SEEN) == "true"

    override suspend fun markSuggestionsSeen() = setValue(KEY_SUGGESTED_SEEN, "true")

    override suspend fun clearAll() = clearValues()

    private fun unlockKey(packageName: String): String = "$KEY_UNLOCK_PREFIX$packageName"

    private companion object {
        const val DELIM = "\n"
        const val KEY_LOCKED = "locked_packages"
        const val KEY_METHOD = "lock_method"
        const val KEY_RELOCK = "relock_delay_ms"
        const val KEY_INTRUDER_ON = "intruder_enabled"
        const val KEY_INTRUDER_N = "intruder_threshold"
        const val KEY_SUGGESTED_SEEN = "suggestions_seen"
        const val KEY_UNLOCK_PREFIX = "unlock_at_"
    }
}
