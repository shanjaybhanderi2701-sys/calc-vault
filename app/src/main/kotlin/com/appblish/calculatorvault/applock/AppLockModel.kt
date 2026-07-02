package com.appblish.calculatorvault.applock

/**
 * The unlock method the AppLock screen challenges with. Mirrors the deck's method
 * switcher (Passcode / Pattern / PIN / Biometric) and the xlock role-model lock screen
 * ("Change password type"). Phase 3 fully wires [Pin] and [Biometric]; [Pattern] and
 * [Passcode] render their entry surface and fall back to the vault PIN for verification
 * (the vault has a single secret — the calculator PIN — so every method resolves against
 * it until per-method secrets land in Phase 5 hardening).
 */
enum class LockMethod {
    Pin,
    Pattern,
    Passcode,
    Biometric,
    ;

    val displayName: String
        get() =
            when (this) {
                Pin -> "PIN"
                Pattern -> "Pattern"
                Passcode -> "Passcode"
                Biometric -> "Biometric"
            }
}

/**
 * The three-way filter on the AppLock list, matching the deck's **All / Unlocked /
 * Locked** segmented control (and xlock's Unlocked/Locked tabs).
 */
enum class AppLockFilter {
    All,
    Unlocked,
    Locked,
    ;

    val displayName: String
        get() =
            when (this) {
                All -> "All"
                Unlocked -> "Unlocked"
                Locked -> "Locked"
            }
}

/**
 * A launchable app on the device, as surfaced in the AppLock list and picker. [icon] is a
 * platform drawable loaded lazily by [AppInventory]; it is null in previews/tests where no
 * PackageManager is present, so the UI must degrade to a letter chip.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val locked: Boolean = false,
    val suggested: Boolean = false,
    val system: Boolean = false,
)

/**
 * User-tunable AppLock behaviour. Persisted by [AppLockStore]. Defaults follow the
 * flow-logic doc: lock with the vault PIN, re-lock immediately on app switch, intruder
 * capture on the 3rd wrong attempt once the owner enables it.
 */
data class AppLockSettings(
    val lockMethod: LockMethod = LockMethod.Pin,
    /** Grace window after a correct unlock before the app re-locks on the next foreground. */
    val relockDelayMs: Long = 0L,
    /** Intruder Selfie is opt-in; the camera permission is only requested when enabled. */
    val intruderEnabled: Boolean = false,
    /** Wrong-attempt count that triggers a capture. Board reference: capture on repeated failure. */
    val intruderThreshold: Int = 3,
) {
    companion object {
        val RELOCK_OPTIONS =
            listOf(
                0L to "Immediately",
                30_000L to "After 30 seconds",
                60_000L to "After 1 minute",
                300_000L to "After 5 minutes",
            )
        const val MIN_THRESHOLD = 1
        const val MAX_THRESHOLD = 5
    }
}
