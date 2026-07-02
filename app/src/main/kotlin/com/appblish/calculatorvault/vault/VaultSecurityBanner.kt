package com.appblish.calculatorvault.vault

/**
 * "Your device at risk" home-banner gating (APP-207). Parity with the reference app xlock:
 * the banner appears **only when All Files Access is missing** and is hidden entirely once
 * it is granted (no standing scare copy). Per the board scope refinement, All Files Access
 * is the *only* mandatory vault permission and the *sole* gate for this banner — camera is
 * an opt-in app-lock permission for Intruder Selfie and is deliberately **not** surfaced
 * here, and no other permission (contacts, etc.) gates it.
 *
 * This object holds the pure decision — whether the banner should show, given the live
 * grant state — so it is unit-testable with no Android dependency. The Compose side
 * ([VaultHomeScreen]) resolves the live [State] from the OS on every `ON_RESUME` and
 * renders the banner only when [firstMissing] is non-null.
 */
object VaultSecurityBanner {
    /** The only permission the banner watches. */
    enum class Permission { ALL_FILES_ACCESS }

    /** The missing permission, with the copy the banner shows for it. */
    data class Warning(
        val permission: Permission,
        val title: String,
        val message: String,
    )

    /** Live grant state of the sole watched permission. */
    data class State(
        val hasAllFilesAccess: Boolean,
    )

    /**
     * A [Warning] when All Files Access is missing, or `null` once it is granted — in which
     * case the banner is hidden. This is the whole gate: the banner tracks nothing else.
     */
    fun firstMissing(state: State): Warning? =
        if (!state.hasAllFilesAccess) {
            Warning(
                permission = Permission.ALL_FILES_ACCESS,
                title = "Your device is at risk",
                message = "Allow All Files Access so CalcVault can hide and protect your files",
            )
        } else {
            null
        }
}
