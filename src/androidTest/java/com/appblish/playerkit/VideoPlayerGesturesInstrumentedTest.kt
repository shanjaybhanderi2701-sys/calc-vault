package com.appblish.playerkit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * On-device (APP-408 DoD gate) verification of the shared video surface's gesture dispatcher
 * ([Modifier.videoPlayerGestures]) — the piece that adds pinch-to-zoom to a consuming app’s video pages
 * (APP-400 P1 "pinch-to-zoom everywhere"). Drives the REAL dispatcher wired onto [VideoZoomState] /
 * [VideoZoomMath] with synthetic multi-touch, so what runs here is exactly the code the ExoPlayer
 * host mounts. Raw playback (decode / scrub / double-tap-seek / aspect) is verified manually on the
 * emulator; this locks the two gestures that need multi-touch injection down as a regression guard.
 */
class VideoPlayerGesturesInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun content(state: VideoZoomState, onDoubleTapSeek: (VideoGestureMath.Zone) -> Unit = {}) {
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .testTag("surface")
                    .videoPlayerGestures(
                        state = state,
                        onToggleChrome = {},
                        onDoubleTapSeek = onDoubleTapSeek,
                    ),
            )
        }
    }

    @Test
    fun pinchOut_zoomsThePlayerSurfacePastOneX() {
        val state = VideoZoomState()
        content(state)
        composeRule.waitForIdle()
        assertFalse("surface starts un-zoomed at 1×", state.isZoomed)

        composeRule.onNodeWithTag("surface").performTouchInput {
            // Two pointers spreading apart from the centre = pinch-out (zoom in).
            pinch(
                start0 = center + Offset(-40f, 0f),
                end0 = center + Offset(-140f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(140f, 0f),
            )
        }
        composeRule.waitForIdle()

        assertTrue("pinch-out drives VideoZoomState past 1×", state.isZoomed)
        assertTrue("scale climbed above the 1× floor", state.scale > VideoZoomMath.MIN_SCALE)
        assertTrue("scale stays within the tested ceiling", state.scale <= VideoZoomMath.MAX_SCALE)
    }

    @Test
    fun doubleTap_rightHalf_resolvesToTheForwardSeekZone() {
        val state = VideoZoomState()
        var seekZone: VideoGestureMath.Zone? = null
        content(state) { seekZone = it }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("surface").performTouchInput {
            doubleClick(position = Offset(width * 0.8f, height / 2f))
        }
        composeRule.waitForIdle()

        assertEquals("double-tap on the right half seeks forward", VideoGestureMath.Zone.RIGHT, seekZone)
    }
}
