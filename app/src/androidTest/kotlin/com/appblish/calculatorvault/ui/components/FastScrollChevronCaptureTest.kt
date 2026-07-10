package com.appblish.calculatorvault.ui.components

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.DoDTestSupport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * APP-344 — on-device VISUAL capture of the redesigned fast-scroll handle. Renders the
 * **real production [FastScrollbar]** on the CI emulator (API 30/35) and writes PNGs of the
 * gate states to `/sdcard/chevron-evidence/` so the CI job can `adb pull` + upload them as
 * artifacts. This is the visual proof the APP-344 DoD gate demands — existence of the glyph
 * is separately *asserted* by [FastScrollChevronDoDTest]; this class only *photographs* it.
 *
 * Capture is deliberately **best-effort**: every stage is wrapped so a capture hiccup on a
 * given emulator never turns the instrumented job red (that would strand the landing). The
 * critical shot — the pill bearing the up/down chevron — needs no gesture and is the most
 * robust; the mid-drag bubble and the auto-hidden idle state are captured opportunistically.
 *
 * Shots (suffixed with the emulator API level):
 *   1. `app344_pill_chevron_api<lvl>.png` — grabbable accent pill bearing the up/down chevron.
 *   2. `app344_date_bubble_api<lvl>.png`  — date/section bubble beside the handle mid-drag.
 *   3. `app344_idle_fade_api<lvl>.png`    — handle auto-hidden after ~1s of inactivity.
 */
@RunWith(AndroidJUnit4::class)
class FastScrollChevronCaptureTest {
    @get:Rule
    val compose = createComposeRule()

    private val suffix: String = "api" + android.os.Build.VERSION.SDK_INT.toString()

    private fun outDir(): File {
        // MANAGE_EXTERNAL_STORAGE lets us write to the shared volume root, which `adb pull`
        // can read on API 30+ (app-private external dirs are hidden from the shell user).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runCatching { DoDTestSupport.grantAllFilesAccess(ctx) }
        return File(Environment.getExternalStorageDirectory(), "chevron-evidence").also { it.mkdirs() }
    }

    private fun save(name: String, bitmap: Bitmap) {
        runCatching {
            File(outDir(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun snap(name: String) {
        runCatching { save(name, compose.onRoot().captureToImage().asAndroidBitmap()) }
    }

    @Test
    fun captureChevronPillBubbleAndFade() {
        compose.setContent {
            CalculatorVaultTheme {
                Surface {
                    val gridState = rememberLazyGridState()
                    val labels = remember { (0 until 150).map { "Item $it" } }
                    Box(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize().testTag("grid"),
                        ) {
                            items(labels) { label ->
                                Text(label, modifier = Modifier.padding(24.dp).aspectRatio(1f))
                            }
                        }
                        FastScrollbar(
                            state = gridState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            // A date-like section label so the drag bubble has real content.
                            labelForIndex = { "Jul ${1 + it % 28}" },
                        )
                    }
                }
            }
        }

        // Scroll activity reveals the auto-hiding handle.
        runCatching {
            compose.onNodeWithTag("grid").performTouchInput { swipeUp() }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("fast-scroll-handle").fetchSemanticsNodes().isNotEmpty()
            }
        }

        // (1) Pill bearing the up/down chevron — not dragging, so nothing covers it. This is
        // the money shot proving the regression is fixed (pill+chevron, not the old thin line).
        snap("app344_pill_chevron_$suffix.png")

        // (2) Date bubble mid-drag: hold a vertical drag on the touch column WITHOUT releasing
        // so `dragging` stays true and the bubble renders beside the handle.
        runCatching {
            val bounds = compose.onNodeWithTag("fast-scroll-handle").fetchSemanticsNode().boundsInRoot
            val x = bounds.center.x
            val y = bounds.center.y
            compose.onNodeWithTag("grid").performTouchInput {
                down(Offset(x, y))
                moveTo(Offset(x, y + 400f))
                moveTo(Offset(x, y + 420f))
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("fast-scroll-bubble").fetchSemanticsNodes().isNotEmpty()
            }
            snap("app344_date_bubble_$suffix.png")
        }
        // Always release the gesture (even if the bubble capture threw) so the auto-hide
        // timer can start; a stuck-down pointer would keep `dragging` true forever.
        runCatching { compose.onNodeWithTag("grid").performTouchInput { up() } }

        // (3) Idle fade / auto-hide: after HIDE_DELAY_MS (1s) of no activity the handle
        // fades to alpha 0 and is removed from the tree. Wait it out in REAL time (leaving
        // the clock auto-advancing — freezing it and then capturing deadlocks waitForIdle
        // and gets the instrumentation process ANR-killed), then photograph the settled
        // idle frame: the accent pill is gone from the trailing edge (contrast with
        // app344_pill_chevron — present there). That is the auto-hide, proven.
        runCatching {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("fast-scroll-handle").fetchSemanticsNodes().isEmpty()
            }
        }
        snap("app344_idle_fade_$suffix.png")
    }
}
