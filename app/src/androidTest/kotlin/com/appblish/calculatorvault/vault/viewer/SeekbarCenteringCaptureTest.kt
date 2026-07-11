package com.appblish.calculatorvault.vault.viewer

import android.graphics.Bitmap
import android.os.Environment
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
 * CalcVault Phase B · APP-395 (APP-380 round 4) — the **cropped/zoomed** seekbar evidence the owner
 * asked for. The round-3 rejection was partly a testing complaint: the only proof was a low-res
 * full-frame screenshot where a few-px vertical thumb offset is invisible.
 *
 * This captures JUST the seekbar strip (via `captureToImage()` on the seekbar node — not the whole
 * screen) and scales it up 6× with nearest-neighbour so individual pixels are legible, making the
 * thumb-vs-track vertical alignment obvious. Two shots per API level:
 *   1. `app395_seekbar_rest_<api>.png`  — thumb parked mid-track, at rest.
 *   2. `app395_seekbar_drag_<api>.png`  — thumb mid-drag (finger held down, scrubbed right).
 *
 * The centring is separately *asserted* to ±1px by [SeekbarThumbCenteringDoDTest]; this class only
 * *photographs* it for owner re-review. Every stage is best-effort so a capture hiccup never reddens
 * the instrumented job.
 */
@RunWith(AndroidJUnit4::class)
class SeekbarCenteringCaptureTest {
    @get:Rule
    val compose = createComposeRule()

    private val rootTag = "seekbar_root"
    private val suffix: String = "api${android.os.Build.VERSION.SDK_INT}"
    private val zoom = 6

    private fun outDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runCatching { DoDTestSupport.grantAllFilesAccess(ctx) }
        return File(Environment.getExternalStorageDirectory(), "app395-evidence").also { it.mkdirs() }
    }

    private fun save(
        name: String,
        bitmap: Bitmap,
    ) {
        runCatching {
            File(outDir(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Capture the seekbar node and pixel-zoom it (nearest-neighbour) so alignment is legible. */
    private fun snapSeekbar(name: String) {
        runCatching {
            compose.waitForIdle()
            val bmp = compose.onNodeWithTag(rootTag).captureToImage().asAndroidBitmap()
            val zoomed = Bitmap.createScaledBitmap(bmp, bmp.width * zoom, bmp.height * zoom, false)
            save(name, zoomed)
        }
    }

    private fun setSeekbar(initial: Float): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.setContent {
            CalculatorVaultTheme {
                // A dark strip mirrors the video-player bottom-bar backdrop the seekbar sits on.
                Surface(color = Color(0xFF101010)) {
                    var fraction by remember { mutableFloatStateOf(initial) }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
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
        return compose.onNodeWithTag(rootTag)
    }

    /** (1) At-rest shot: thumb parked mid-track. */
    @Test
    fun captureSeekbarAtRest() {
        setSeekbar(0.42f)
        snapSeekbar("app395_seekbar_rest_$suffix.png")
    }

    /** (2) Mid-drag shot: press near the left and hold a scrub toward the right, then capture. */
    @Test
    fun captureSeekbarMidDrag() {
        val node = setSeekbar(0f)
        runCatching {
            node.performTouchInput {
                down(centerLeft)
                moveTo(center)
                moveTo(centerRight)
                // stop short of the very end so the thumb sits over the track, not the edge
                moveTo(Offset(width * 0.72f, centerRight.y))
            }
        }
        snapSeekbar("app395_seekbar_drag_$suffix.png")
        runCatching { node.performTouchInput { up() } }
    }
}
