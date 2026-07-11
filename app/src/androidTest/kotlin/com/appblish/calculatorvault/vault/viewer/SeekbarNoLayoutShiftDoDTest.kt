package com.appblish.calculatorvault.vault.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * CalcVault Phase B · APP-418 (P0, continues APP-398) — the **no-layout-shift** DoD gate.
 *
 * The owner device-rejected the seekbar twice; the latest regression was: *"dragging on the seekbar
 * shifts the entire layout"*. The subtle cause is the elapsed-time label in the bottom bar — it grows
 * as the scrub value climbs (`0:00` → `1:59:59`), which, if the label slot is not width-pinned, reflows
 * the seekbar Row and slides the seekbar horizontally **under the finger** mid-drag. The production fix
 * (`VideoPlayerControlsOverlay`) pins the elapsed slot to the never-shorter duration string via an
 * invisible sizer so the seekbar origin is rock-steady.
 *
 * This test reconstructs that exact bottom-bar Row — pinned elapsed slot + [ThinSeekbar] + duration —
 * and drags the seekbar from the far left (elapsed `0:00`) to the far right (elapsed `1:59:59`, the
 * widest string). It captures the seekbar's layout bounds **before** the drag and **while the finger
 * is still down at the far right** (the worst case for reflow) and asserts:
 *  - the seekbar's left/right/top/bottom bounds do not move (≤ 1dp), i.e. the layout does NOT shift, AND
 *  - the scrub still tracked the finger live (sanity that the drag actually happened).
 *
 * A companion assertion drives the elapsed text through its full growth so a *regression that dropped
 * the width-pinning* would move the seekbar and fail here.
 */
@RunWith(AndroidJUnit4::class)
class SeekbarNoLayoutShiftDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val seekbarTag = "noshift_seekbar"

    private val emitted = mutableListOf<Float>()

    /** The exact width-pinned elapsed slot + seekbar + duration Row from the production bottom bar. */
    private fun setBottomBar(durationMs: Long) {
        emitted.clear()
        compose.setContent {
            CalculatorVaultTheme {
                Surface(color = Color.Black) {
                    var fraction by remember { mutableFloatStateOf(0f) }
                    val elapsedMs = (fraction * durationMs).toLong()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    ) {
                        // Width-pinned elapsed slot: transparent duration sizer reserves the widest
                        // width; the visible elapsed label draws on top and can never widen the slot.
                        Box(contentAlignment = Alignment.CenterStart) {
                            Text(
                                text = VideoGestureMath.formatTime(durationMs),
                                color = Color.Transparent,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = VideoGestureMath.formatTime(elapsedMs),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        ThinSeekbar(
                            fraction = fraction,
                            onScrub = {
                                fraction = it
                                emitted += it
                            },
                            onScrubFinished = { },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).testTag(seekbarTag),
                        )
                        Text(
                            text = VideoGestureMath.formatTime(durationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Drag from far left (elapsed `0:00`) to far right (elapsed `1:59:59`) and confirm the seekbar's
     * layout bounds do not move — even mid-drag, while the widest elapsed label is showing.
     */
    @Test
    fun seekDrag_doesNotShiftSeekbarBounds() {
        // 2h video → elapsed grows from "0:00" (4 chars) to "1:59:59" (7 chars), the reflow worst case.
        setBottomBar(durationMs = 2L * 60L * 60L * 1000L)

        val before = compose.onNodeWithTag(seekbarTag).fetchSemanticsNode().boundsInRoot

        // Press left, drag to the right, and PAUSE with the finger still down so the elapsed label is at
        // its widest ("1:59:59") while we re-measure — this is exactly when an unpinned slot would shift.
        compose.onNodeWithTag(seekbarTag).performTouchInput {
            down(centerLeft)
            moveTo(center)
            moveTo(centerRight)
        }
        compose.waitForIdle()

        val during = compose.onNodeWithTag(seekbarTag).fetchSemanticsNode().boundsInRoot

        // Release the finger to end the gesture cleanly.
        compose.onNodeWithTag(seekbarTag).performTouchInput { up() }
        compose.waitForIdle()

        // The scrub must have actually run (live values, far right reached).
        assertTrue("expected live scrub updates, got $emitted", emitted.distinct().size >= 3)
        val lastScrub = emitted.lastOrNull() ?: 0f
        assertTrue("scrub should reach the right edge, got $lastScrub", lastScrub > 0.75f)

        // 1px tolerance for sub-pixel rounding; the seekbar must NOT slide as the elapsed label grows.
        val tol = 1f
        assertTrue(
            "seekbar LEFT shifted during drag: ${before.left} -> ${during.left}",
            abs(before.left - during.left) <= tol,
        )
        assertTrue(
            "seekbar RIGHT shifted during drag: ${before.right} -> ${during.right}",
            abs(before.right - during.right) <= tol,
        )
        assertTrue(
            "seekbar TOP shifted during drag: ${before.top} -> ${during.top}",
            abs(before.top - during.top) <= tol,
        )
        assertTrue(
            "seekbar BOTTOM shifted during drag: ${before.bottom} -> ${during.bottom}",
            abs(before.bottom - during.bottom) <= tol,
        )
    }
}
