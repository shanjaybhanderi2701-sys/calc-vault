package com.appblish.calculatorvault.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The FLAG_SECURE decision (PIN Recovery W4, spec §6). Exhaustively covers the release branch
 * (`isDebugBuild = false`) — the DoD's "verified in a release build" — plus the debug capture
 * gate, so [com.appblish.calculatorvault.MainActivity] can stay a thin apply-the-boolean shim.
 */
class ScreenshotPolicyTest {
    private fun secure(
        debug: Boolean,
        userAllows: Boolean,
        debugGate: Boolean,
    ) = ScreenshotPolicy.shouldSecureWindow(
        isDebugBuild = debug,
        userAllowsScreenshots = userAllows,
        debugCaptureGateEnabled = debugGate,
    )

    // --- Release build: only the user's "Allow screenshots" setting matters (debug gate ignored).

    @Test
    fun `release defaults to secure`() {
        assertThat(secure(debug = false, userAllows = false, debugGate = false)).isTrue()
    }

    @Test
    fun `release with allow-screenshots on is not secure`() {
        assertThat(secure(debug = false, userAllows = true, debugGate = false)).isFalse()
    }

    @Test
    fun `release ignores the debug capture gate`() {
        // Even with the device-global set, a release build stays secure unless the user opted in.
        assertThat(secure(debug = false, userAllows = false, debugGate = true)).isTrue()
        assertThat(secure(debug = false, userAllows = true, debugGate = true)).isFalse()
    }

    // --- Debug build: the capture gate coexists with the setting (either one drops FLAG_SECURE).

    @Test
    fun `debug defaults to secure when neither gate is on`() {
        assertThat(secure(debug = true, userAllows = false, debugGate = false)).isTrue()
    }

    @Test
    fun `debug capture gate alone drops FLAG_SECURE`() {
        assertThat(secure(debug = true, userAllows = false, debugGate = true)).isFalse()
    }

    @Test
    fun `debug user setting alone drops FLAG_SECURE`() {
        assertThat(secure(debug = true, userAllows = true, debugGate = false)).isFalse()
    }
}
