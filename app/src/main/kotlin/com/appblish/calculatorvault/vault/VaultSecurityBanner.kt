package com.appblish.calculatorvault.vault

/**
 * "Your device at risk" home-banner gating (APP-207). Parity with the reference app xlock:
 * the banner appears **only when a required permission is actually missing** and names that
 * specific permission; once the full *applicable* required set is granted the banner is
 * hidden entirely (no standing scare copy).
 *
 * This object holds the pure decision — which (if any) permission the banner should warn
 * about, given the live grant state — so it is unit-testable with no Android dependency.
 * The Compose side ([VaultHomeScreen]) resolves the live [State] from the OS on every
 * `ON_RESUME` and renders the banner only when [firstMissing] is non-null.
 */
object VaultSecurityBanner {
    /** The permissions the banner watches, in the order they are surfaced to the user. */
    enum class Permission { ALL_FILES_ACCESS, CAMERA }

    /** A single missing permission, with the copy the banner shows for it. */
    data class Warning(
        val permission: Permission,
        val title: String,
        val message: String,
    )

    /**
     * Live grant state of the watched permissions. A `null` field means the permission is
     * **not applicable** on this device/configuration (e.g. camera only matters once the
     * Intruder Selfie feature is enabled) and is therefore never a reason to show the banner.
     */
    data class State(
        val hasAllFilesAccess: Boolean,
        val hasCamera: Boolean? = null,
    )

    /**
     * The single highest-priority missing permission, or `null` when every *applicable*
     * required permission is granted — in which case the banner is hidden. Priority order:
     * All Files Access (the core vault requirement) → Camera (intruder capture).
     */
    fun firstMissing(state: State): Warning? =
        when {
            !state.hasAllFilesAccess ->
                Warning(
                    permission = Permission.ALL_FILES_ACCESS,
                    title = "Your device is at risk",
                    message = "Allow All Files Access so CalcVault can hide and protect your files",
                )
            state.hasCamera == false ->
                Warning(
                    permission = Permission.CAMERA,
                    title = "Your device is at risk",
                    message = "Allow camera access so Intruder Selfie can catch snoopers",
                )
            else -> null
        }
}
