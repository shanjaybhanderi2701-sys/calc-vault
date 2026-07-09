package com.appblish.calculatorvault

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.appblish.calculatorvault.navigation.VaultNavHost
import com.appblish.calculatorvault.settings.ScreenshotPolicy
import com.appblish.calculatorvault.settings.SettingsGraph
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.ui.theme.VaultTheme
import kotlinx.coroutines.launch

/**
 * Single-activity host. Owns nothing but the Compose tree; navigation between the
 * calculator disguise, PIN entry, and the vault lives in [VaultNavHost].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Privacy hardening (APP-205 / spec §10): mark the window secure so no screenshot /
        // screen-record can capture vault content and the recents thumbnail is blanked, and
        // disable the recents snapshot outright on API 33+. FLAG_SECURE is the PRIMARY
        // privacy layer; hiding the task from recents entirely is an opt-in setting applied
        // below (OFF by default — a calculator that vanishes from recents is more
        // suspicious, spec §10 / APP-225).
        // Every build type applies the flags by default, so the instrumented §10 proof
        // (FlagSecureDoDTest) runs against the debug variant CI actually builds (APP-241).
        // Debug-only escape hatch for bug-report screenshots (APP-233): a device-global
        // setting an operator flips explicitly per capture session —
        //   adb shell settings put global calcvault_allow_screenshots 1
        // (then relaunch; delete the setting to restore protection). Release ignores it.
        // The release-facing "Allow screenshots" toggle (PIN Recovery W4, default OFF) is the
        // other input; either gate being on drops FLAG_SECURE (see [ScreenshotPolicy]).
        applyScreenshotPolicy()
        applyPersistedHideFromRecents()
        // Edge-to-edge with VISIBLE system bars (APP-225 P2-4, supersedes APP-204's
        // immersive hide-bars mode): transparent bars with light icons over the dark theme.
        // The Surface paints the canvas edge-to-edge (so the strips behind the transparent
        // bars stay on-theme) while the inner Box pads the entire nav tree by
        // WindowInsets.safeDrawing — one root-level pad keeps every screen clear of the
        // status bar, display cutout, and navigation bar without per-screen inset handling.
        applyEdgeToEdge()
        setContent {
            CalculatorVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = VaultTheme.colors.canvas,
                ) {
                    Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                        VaultNavHost()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate FLAG_SECURE on every resume so flipping the "Allow screenshots" toggle in
        // Settings takes effect the moment the user returns here, without a relaunch. (The
        // Settings screen also flips the flag on the live window immediately for instant
        // feedback; this is the durable re-apply from the persisted value.)
        applyScreenshotPolicy()
    }

    /**
     * Apply (or clear) `FLAG_SECURE` from the current [ScreenshotPolicy] decision. Secure by
     * default; dropped only when the release toggle or the debug capture gate allows screenshots.
     */
    private fun applyScreenshotPolicy() {
        val secure =
            ScreenshotPolicy.shouldSecureWindow(
                isDebugBuild = BuildConfig.DEBUG,
                userAllowsScreenshots = SettingsGraph.allowScreenshotsEnabled,
                debugCaptureGateEnabled = debugScreenshotsEnabled(),
            )
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!secure)
        }
    }

    /** True only when the debug screenshot override (APP-233) was explicitly set via adb. */
    private fun debugScreenshotsEnabled(): Boolean =
        runCatching {
            Settings.Global.getInt(contentResolver, "calcvault_allow_screenshots", 0) == 1
        }.getOrDefault(false)

    /** Re-apply the opt-in "hide from recents" setting to this task on every launch. */
    private fun applyPersistedHideFromRecents() {
        lifecycleScope.launch {
            val enabled = runCatching { SettingsGraph.settingsStore.load().hideFromRecentsEnabled }.getOrDefault(false)
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.appTasks?.firstOrNull()?.setExcludeFromRecents(enabled)
        }
    }
}
