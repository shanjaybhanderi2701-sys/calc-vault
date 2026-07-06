package com.appblish.calculatorvault

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
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
        // Debug builds skip the secure-window flags (APP-233): screenshots must work for
        // bug reporting during stabilization; release builds always get the protection.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(false)
            }
        }
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

    /** Re-apply the opt-in "hide from recents" setting to this task on every launch. */
    private fun applyPersistedHideFromRecents() {
        lifecycleScope.launch {
            val enabled = runCatching { SettingsGraph.settingsStore.load().hideFromRecentsEnabled }.getOrDefault(false)
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.appTasks?.firstOrNull()?.setExcludeFromRecents(enabled)
        }
    }
}
