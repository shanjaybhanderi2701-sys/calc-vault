package com.appblish.calculatorvault.vault.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalcVault Phase B · APP-398 (APP-380 round 5a) — the DoD gate for **press-and-drag scrubbing**.
 *
 * The owner rejected round 4 because the thin seekbar was tap-only ("the seek bar is not draggable —
 * you cannot press-and-drag to scrub"). This test drives the real production [ThinSeekbar] gesture
 * layer and proves the standard MX/YouTube behaviour:
 *  - pressing and dragging the thumb reports a **continuously updating** fraction that follows the
 *    finger (live scrub position, not a single jump),
 *  - the final committed position matches where the finger was released,
 *  - `onScrubFinished` fires exactly once on release (the parent's single `seekTo` commit),
 *  - a plain tap still jumps to the touched position (tap-to-jump preserved).
 *
 * It records every fraction the seekbar emits so we can assert the drag was *live* (multiple distinct
 * values), which a tap-only implementation could never satisfy.
 */
@RunWith(AndroidJUnit4::class)
class SeekbarDragScrubDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val rootTag = "seekbar_root"

    private val emitted = mutableListOf<Float>()
    private var finishedCount = 0
    private var committed = -1f

    private fun setSeekbar(initial: Float) {
        emitted.clear()
        finishedCount = 0
        committed = -1f
        compose.setContent {
            CalculatorVaultTheme {
                Surface(color = Color.Black) {
                    var fraction by remember { mutableFloatStateOf(initial) }
                    Box(Modifier.fillMaxWidth().padding(24.dp)) {
                        ThinSeekbar(
                            fraction = fraction,
                            onScrub = {
                                fraction = it
                                emitted += it
                            },
                            onScrubFinished = {
                                finishedCount++
                                committed = fraction
                            },
                            modifier = Modifier.fillMaxWidth().testTag(rootTag),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Press near the left, drag across to the right in steps, release: fraction tracks the finger. */
    @Test
    fun pressDrag_updatesPositionLive_andCommitsOnRelease() {
        setSeekbar(0f)
        compose.onNodeWithTag(rootTag).performTouchInput {
            down(centerLeft)
            moveTo(center)
            moveTo(centerRight)
            up()
        }
        compose.waitForIdle()

        // Live: the drag must have produced several distinct fractions, not one jump.
        val distinct = emitted.distinct()
        assertTrue("expected live scrub updates, got $emitted", distinct.size >= 3)

        // Grab-on-press: first emission is near the left edge (thumb latched under the finger).
        assertTrue("first emit should be near start, was ${emitted.first()}", emitted.first() < 0.25f)
        // Mid of the drag reached roughly the centre.
        assertTrue("expected a mid-drag value near 0.5, got $emitted", emitted.any { it in 0.35f..0.65f })
        // Released at the right edge → final committed fraction is near 1.
        assertTrue("expected final near end, was ${emitted.last()}", emitted.last() > 0.75f)

        // Exactly one commit (single seekTo) on release, at the released position.
        assertEquals("onScrubFinished must fire once", 1, finishedCount)
        assertTrue("committed=$committed should equal final scrub ${emitted.last()}", committed > 0.75f)
    }

    /** Tap-to-jump is preserved: a down+up with no movement jumps to the touched fraction. */
    @Test
    fun tap_stillJumps() {
        setSeekbar(0f)
        compose.onNodeWithTag(rootTag).performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()

        assertTrue("tap should jump near mid, emitted=$emitted", emitted.any { it in 0.35f..0.65f })
        assertEquals("tap must commit once", 1, finishedCount)
    }
}
