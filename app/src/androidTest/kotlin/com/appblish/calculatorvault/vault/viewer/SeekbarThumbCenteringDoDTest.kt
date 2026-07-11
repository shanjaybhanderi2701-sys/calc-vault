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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * CalcVault Phase B · APP-395 (APP-380 round 4) — the **"how you test"** gate the owner demanded.
 *
 * Proves that the thin video seekbar's round scrubber thumb is vertically centred on the 2dp track:
 * the thumb's vertical centre must equal the track's vertical centre within ±1px. This is the exact
 * defect the owner rejected round 3 for ("seek bar thumb not centred"), so it is now locked by an
 * on-device layout assertion that can't silently regress — checked at rest at three progress
 * positions (start / mid / end) AND while a drag is actively in progress.
 *
 * The assertion reads the real production [ThinSeekbar] node bounds via the compose semantics tree
 * (`boundsInRoot`), so it measures the actually-placed pixels, not the source intent.
 */
@RunWith(AndroidJUnit4::class)
class SeekbarThumbCenteringDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val rootTag = "seekbar_root"

    /** Render the real seekbar with a mutable fraction so drags move the thumb. */
    private fun setSeekbar(initial: Float) {
        compose.setContent {
            CalculatorVaultTheme {
                Surface(color = Color.Black) {
                    var fraction by remember { mutableFloatStateOf(initial) }
                    Box(Modifier.fillMaxWidth().padding(24.dp)) {
                        ThinSeekbar(
                            fraction = fraction,
                            onScrub = { fraction = it },
                            onScrubFinished = {},
                            modifier = Modifier.fillMaxWidth().testTag(rootTag),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Thumb centreY must equal track centreY within ±1px (DoD tolerance). */
    private fun assertThumbCentredOnTrack(where: String) {
        val thumb = compose.onNodeWithTag(SEEK_THUMB_TAG).fetchSemanticsNode().boundsInRoot
        val track = compose.onNodeWithTag(SEEK_TRACK_TAG).fetchSemanticsNode().boundsInRoot
        val diff = abs(thumb.center.y - track.center.y)
        assertTrue(
            "[$where] thumb centreY=${thumb.center.y} vs track centreY=${track.center.y} " +
                "diff=${diff}px must be <= 1px",
            diff <= 1.0f,
        )
    }

    @Test
    fun thumbCentred_atStart() {
        setSeekbar(0f)
        assertThumbCentredOnTrack("start")
    }

    @Test
    fun thumbCentred_atMid() {
        setSeekbar(0.5f)
        assertThumbCentredOnTrack("mid")
    }

    @Test
    fun thumbCentred_atEnd() {
        setSeekbar(1f)
        assertThumbCentredOnTrack("end")
    }

    /** While a drag is in flight (finger still down) the thumb must remain centred. */
    @Test
    fun thumbCentred_duringActiveDrag() {
        setSeekbar(0f)
        // Press near the left and drag toward the centre WITHOUT releasing, so the thumb has moved
        // and the gesture is genuinely in progress when we measure.
        compose.onNodeWithTag(rootTag).performTouchInput {
            down(centerLeft)
            moveTo(center)
        }
        compose.waitForIdle()
        assertThumbCentredOnTrack("mid-drag")
        // Release so the gesture doesn't leak into the next test.
        compose.onNodeWithTag(rootTag).performTouchInput { up() }
    }
}
