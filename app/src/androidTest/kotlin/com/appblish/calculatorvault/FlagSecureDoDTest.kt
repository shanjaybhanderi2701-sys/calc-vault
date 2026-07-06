package com.appblish.calculatorvault

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-1 DoD proof for **privacy hardening** (APP-225, build spec §10 / APP-205): the
 * app's single activity marks its window `FLAG_SECURE` from `onCreate`, so no
 * screenshot / screen-record / recents thumbnail can ever capture vault content —
 * asserted directly on the live window attributes via ActivityScenario, not by eyeballing
 * a black screencap (board hard rule #2).
 *
 * APP-233 carved out ONE exception: debug builds skip the flag so bug-report screenshots
 * work. The DoD assertion is therefore build-type-aware — release-shaped builds MUST carry
 * the flag, debug builds MUST NOT (proving the gate is actually wired, not just absent).
 */
@RunWith(AndroidJUnit4::class)
class FlagSecureDoDTest {
    @Test
    fun mainActivityWindowCarriesFlagSecurePerBuildTypePolicy() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val secure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
                if (BuildConfig.DEBUG) {
                    // APP-233: debug builds intentionally allow capture for bug evidence.
                    assertThat(secure).isEqualTo(0)
                } else {
                    assertThat(secure).isEqualTo(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}
