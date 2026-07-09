package com.appblish.calculatorvault.settings

/**
 * The single, pure decision for whether the window must carry `FLAG_SECURE` (PIN Recovery
 * W4 / spec §6). Kept free of Android APIs so every branch — release default, the user's
 * "Allow screenshots" toggle, and the debug capture gate — is unit-testable off-device
 * ([ScreenshotPolicyTest]); [com.appblish.calculatorvault.MainActivity] only supplies the
 * three inputs and applies the boolean to the real window.
 *
 * FLAG_SECURE is the privacy default: it blocks screenshots / screen-record of vault content
 * and blanks the recents preview. It is dropped only when screenshots are explicitly allowed
 * by **either**:
 *  - [userAllowsScreenshots] — the release-facing Settings toggle (default OFF), or
 *  - [debugCaptureGateEnabled] on a **debug** build only — the `calcvault_allow_screenshots`
 *    device-global an operator flips for bug-report captures (APP-233). Release ignores it.
 *
 * The two gates coexist: whichever is on wins, so testers keep their debug override while the
 * shipped default (both off) stays secure.
 */
object ScreenshotPolicy {
    /**
     * @param isDebugBuild `BuildConfig.DEBUG`.
     * @param userAllowsScreenshots the persisted "Allow screenshots" setting (release-facing).
     * @param debugCaptureGateEnabled the `calcvault_allow_screenshots` device-global (debug only).
     * @return true when the window must be marked secure (FLAG_SECURE on).
     */
    fun shouldSecureWindow(
        isDebugBuild: Boolean,
        userAllowsScreenshots: Boolean,
        debugCaptureGateEnabled: Boolean,
    ): Boolean {
        val screenshotsAllowed = userAllowsScreenshots || (isDebugBuild && debugCaptureGateEnabled)
        return !screenshotsAllowed
    }
}
