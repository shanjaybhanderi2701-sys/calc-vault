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
import androidx.compose.ui.geometry.Offset
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
 * CalcVault Phase B · APP-434 (APP-417 **round 7**) — the **grab-tolerance** DoD gate.
 *
 * Owner device-rejection (round 7, verbatim intent): *"the seekbar drags correctly now, but the thumb
 * is hard to grab"* — the visual thumb is (correctly) small, but the interactive region demanded
 * precision aiming. The R7 fix decouples the hit area from the visual size: the drawn track/thumb keep
 * their inset ([SEEK_EDGE_PADDING]) but the invisible gesture layer spans the seekbar's FULL width and
 * grabs on the down (jump-to-finger), so a fat-fingered press *offset* from the thumb — or landing in
 * the end gutters that used to be dead — still latches the drag.
 *
 * This drives the **real production [ThinSeekbar] gesture layer** and asserts:
 *  - a press ~24dp *offset* from the thumb centre (finger nowhere near the 12dp thumb) still grabs and
 *    jumps the scrub to the finger, then a drag from there tracks live, and
 *  - a press in the previously-dead end gutters (a couple of px from the very left / very right edge,
 *    past the thumb near an extreme) still latches a drag and scrubs.
 *
 * A regression that re-narrowed the hit area (e.g. re-inset the gesture layer to the drawn track, or
 * dropped the jump-to-finger grab) would stop emitting a scrub for these offset presses and fail here.
 * Complements [SeekbarNoLayoutShiftDoDTest] (≤1px, drawn size unchanged) and
 * [SeekbarSeekOnReleaseDoDTest] (0 seeks during drag / 1 on release), which stay green.
 */
@RunWith(AndroidJUnit4::class)
class SeekbarGrabToleranceDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val tag = "grabtol_seekbar"
    private val emitted = mutableListOf<Float>()
    private var finished = 0

    /** Render the real seekbar with a mutable fraction so drags actually move the thumb. */
    private fun setSeekbar(initial: Float) {
        emitted.clear()
        finished = 0
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
                            onScrubFinished = { finished++ },
                            modifier = Modifier.fillMaxWidth().testTag(tag),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** A press ~24dp to the side of the thumb (not on it) still grabs, jumps to the finger, and drags. */
    @Test
    fun pressOffsetFromThumbCentre_grabsAndScrubs() {
        setSeekbar(0.5f) // thumb parked at the centre

        val thumb = compose.onNodeWithTag(SEEK_THUMB_TAG).fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val offsetPx = with(compose.density) { 24.dp.toPx() }
        // 24dp to the RIGHT of the thumb centre — a fat finger aimed near, but not on, the 12dp thumb.
        val pressX = (thumb.center.x - node.left) + offsetPx
        val bandY = node.height / 2f // vertical centre of the 48dp touch band

        compose.onNodeWithTag(tag).performTouchInput { down(Offset(pressX, bandY)) }
        compose.waitForIdle()

        // Jump-to-finger: the press alone (no move yet) grabbed and scrubbed past the 0.5 start.
        assertTrue("offset press should grab + scrub, emitted=$emitted", emitted.isNotEmpty())
        assertTrue("grab should jump to the finger (> 0.5 start), got ${emitted.first()}", emitted.first() > 0.5f)

        // Now drag further right and release — the grab must have latched a live drag.
        compose.onNodeWithTag(tag).performTouchInput {
            moveTo(Offset(width * 0.9f, bandY))
            up()
        }
        compose.waitForIdle()

        assertTrue("drag should latch + track live, emitted=$emitted", emitted.distinct().size >= 2)
        assertTrue("scrub should reach the right side, got ${emitted.last()}", emitted.last() > 0.75f)
        assertEquals("exactly one release/commit", 1, finished)
    }

    /** A press a couple of px from the far-LEFT edge (the old dead gutter) still latches a drag. */
    @Test
    fun pressInLeftGutter_grabsAndScrubs() {
        setSeekbar(0f) // thumb parked hard left

        val bandY =
            compose
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .boundsInRoot.height / 2f
        compose.onNodeWithTag(tag).performTouchInput { down(Offset(2f, bandY)) }
        compose.waitForIdle()
        // The gutter press grabbed (jump-to-finger clamps to 0f at the extreme).
        assertTrue("left-gutter press should grab, emitted=$emitted", emitted.isNotEmpty())

        // Drag toward the middle/right and release — proves the edge grab latched a real drag.
        compose.onNodeWithTag(tag).performTouchInput {
            moveTo(Offset(width * 0.6f, bandY))
            up()
        }
        compose.waitForIdle()

        assertTrue("edge grab should latch + scrub live, emitted=$emitted", emitted.distinct().size >= 2)
        assertTrue("scrub should move off the left edge, got ${emitted.last()}", emitted.last() > 0.4f)
        assertEquals(1, finished)
    }

    /** A press a couple of px from the far-RIGHT edge (the old dead gutter) still latches a drag. */
    @Test
    fun pressInRightGutter_grabsAndScrubs() {
        setSeekbar(1f) // thumb parked hard right

        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val bandY = node.height / 2f
        compose.onNodeWithTag(tag).performTouchInput { down(Offset(width.toFloat() - 2f, bandY)) }
        compose.waitForIdle()
        assertTrue("right-gutter press should grab, emitted=$emitted", emitted.isNotEmpty())

        // Drag back toward the left and release.
        compose.onNodeWithTag(tag).performTouchInput {
            moveTo(Offset(width * 0.25f, bandY))
            up()
        }
        compose.waitForIdle()

        assertTrue("edge grab should latch + scrub live, emitted=$emitted", emitted.distinct().size >= 2)
        assertTrue("scrub should move off the right edge, got ${emitted.last()}", emitted.last() < 0.6f)
        assertEquals(1, finished)
    }
}
