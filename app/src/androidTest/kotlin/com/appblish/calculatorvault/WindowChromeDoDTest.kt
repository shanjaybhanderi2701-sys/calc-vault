package com.appblish.calculatorvault

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Board-added Phase-1 check #4, chrome half (APP-237/APP-241): **the status bar renders
 * correctly** — APP-235 (P2-4) replaced APP-204's immersive hide-bars mode with
 * edge-to-edge + VISIBLE system bars, and the whole nav tree pads by `safeDrawing` so no
 * screen draws under the bar. Asserted on the live window: the status bar is visible (not
 * hidden by any leftover immersive flag) and reports a real inset for content to pad by.
 */
@RunWith(AndroidJUnit4::class)
class WindowChromeDoDTest {
    @Test
    fun statusBarIsVisibleAndReportsInsets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var visible = false
            var insetTop = -1
            // Insets arrive with the first layout pass; poll briefly instead of asserting
            // inside the very first onActivity callback.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !(visible && insetTop > 0)) {
                scenario.onActivity { activity ->
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    if (insets != null) {
                        visible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
                        insetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                    }
                }
                Thread.sleep(100)
            }
            assertThat(visible).isTrue()
            assertThat(insetTop).isGreaterThan(0)
        }
    }
}
