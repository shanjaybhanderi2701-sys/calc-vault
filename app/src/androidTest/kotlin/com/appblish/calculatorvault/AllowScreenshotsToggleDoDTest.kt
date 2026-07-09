package com.appblish.calculatorvault

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.settings.SettingsGraph
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PIN Recovery W4 DoD proof: the **"Allow screenshots"** toggle flips `FLAG_SECURE` on the
 * live [MainActivity] window. [com.appblish.calculatorvault.MainActivity] re-evaluates the
 * flag from [SettingsGraph.allowScreenshotsEnabled] (the synchronous cache the Settings toggle
 * writes) on `onCreate` / `onResume`, so this drives that cache and asserts the real window
 * attributes — not an eyeballed screencap.
 *
 * The release-only branch of the decision (debug capture gate ignored) is proven exhaustively
 * off-device by `ScreenshotPolicyTest`; here we prove the on-device wiring end to end.
 */
@RunWith(AndroidJUnit4::class)
class AllowScreenshotsToggleDoDTest {
    @After
    fun tearDown() {
        // Restore the secure default so other instrumented tests (e.g. FlagSecureDoDTest) are
        // unaffected by the shared SettingsGraph cache.
        SettingsGraph.cacheAllowScreenshots(false)
    }

    @Test
    fun allowScreenshotsOffKeepsFlagSecure() {
        SettingsGraph.cacheAllowScreenshots(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE)
                    .isEqualTo(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    @Test
    fun allowScreenshotsOnClearsFlagSecure() {
        SettingsGraph.cacheAllowScreenshots(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)
            }
        }
    }
}
