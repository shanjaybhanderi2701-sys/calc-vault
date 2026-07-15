package com.appblish.calculatorvault.vault.viewer

import android.graphics.Bitmap
import android.os.Environment
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.DoDTestSupport
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.playerkit.VideoScaleMath
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * CalcVault Phase B · APP-384 — on-device VISUAL capture of the MX-Player redesign. Renders the
 * **real production** [VideoPlayerControlsOverlay] / [VideoPlayerLockOverlay] / [VideoPlayerSurface]
 * on the CI emulator (API 30/35) and writes PNGs to `/sdcard/app384-evidence/` so CI can
 * `adb pull` + upload them as artifacts (the same evidence path the chevron capture uses). The
 * arrangement itself is *asserted* by [MxPlayerRedesignDoDTest]; this class only *photographs* it.
 *
 * Capture uses the whole-screen [android.app.UiAutomation.takeScreenshot] (not
 * `captureToImage`) so it reliably grabs the ⋯ **DropdownMenu popup** (a separate window) and the
 * [androidx.media3.ui.PlayerView] `AndroidView` surface — both of which a single-root Compose
 * capture cannot. Each shot is its own `@Test` for a fresh activity. Every stage is best-effort
 * (wrapped) so a capture hiccup never turns the instrumented job red.
 *
 * Shots (suffixed with the emulator API level):
 *   1. `app384_bottom_bar_<api>.png`      — full-width seekbar + tidy control row (#3).
 *   2. `app384_overflow_groups_<api>.png` — ⋯ split into Playback settings / File actions (#1).
 *   3. `app384_locked_<api>.png`          — locked state: controls hidden + unlock affordance (#5).
 *   4. `app384_gesture_brightness_<api>.png` — left-edge vertical swipe overlay (#4).
 *   5. `app384_gesture_volume_<api>.png`     — right-edge vertical swipe overlay (#4).
 *   6. `app384_gesture_seek_<api>.png`       — horizontal swipe scrub overlay (#4).
 */
@RunWith(AndroidJUnit4::class)
class MxPlayerRedesignCaptureTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val suffix: String = "api${android.os.Build.VERSION.SDK_INT}"

    private fun outDir(): File {
        val ctx = instrumentation.targetContext
        runCatching { DoDTestSupport.grantAllFilesAccess(ctx) }
        return File(Environment.getExternalStorageDirectory(), "app384-evidence").also { it.mkdirs() }
    }

    private fun writePng(name: String) {
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(outDir(), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Static overlay capture — safe to fully settle (no time-limited indicator on screen). */
    private fun snap(name: String) {
        runCatching {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            // Let the compositor present the frame before grabbing the screen.
            Thread.sleep(500)
            writePng(name)
        }
    }

    /**
     * Gesture-indicator capture — the brightness/volume/seek overlays auto-hide ~650ms after the
     * last drag event, and the auto-advancing compose clock means `waitForIdle` can run past that
     * window. So this only syncs the instrumentation queue (renders the indicator frame) with a
     * short real-time settle, then grabs the screen immediately — no `compose.waitForIdle()`.
     */
    private fun gestureSnap(name: String) {
        runCatching {
            instrumentation.waitForIdleSync()
            Thread.sleep(120)
            writePng(name)
        }
    }

    private fun controller() =
        VideoPlaylistController(
            items =
                listOf(
                    VaultItem("a", VaultCategory.VIDEOS, "First.mp4", "Today", 0L),
                    VaultItem("b", VaultCategory.VIDEOS, "Second.mp4", "Today", 1L),
                ),
            currentIndex = 0,
            orderMode = OrderMode.ORDER,
            onOrderModeChanged = {},
            onSelect = {},
            onNext = {},
            onPrevious = {},
            onCompleted = {},
        )

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun setOverlay(locked: Boolean) {
        compose.setContent {
            CalculatorVaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        VideoPlayerControlsOverlay(
                            player = CaptureInertPlayer(),
                            controlsVisible = true,
                            onToggleControls = {},
                            fileActions =
                                ViewerFileActions(
                                    onBack = {},
                                    onInfo = {},
                                    onShare = {},
                                    onMove = {},
                                    onUnhide = {},
                                    onDelete = {},
                                ),
                            locked = locked,
                            onLockChanged = {},
                            speed = 1.0f,
                            onSpeedChanged = {},
                            aspectMode = VideoScaleMath.AspectMode.FIT,
                            onAspectModeChanged = {},
                            rotationDegrees = 0,
                            onRotationChanged = {},
                            muted = false,
                            onMutedChanged = {},
                            fullscreen = false,
                            onFullscreenChanged = {},
                            playlist = controller(),
                            currentSubtitleLabel = null,
                            onLoadDeviceSubtitle = {},
                            onLoadVaultSubtitle = {},
                            onClearSubtitle = {},
                            onMinimize = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** (1) bottom bar — full-width seekbar + control row. */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun captureBottomBar() {
        setOverlay(locked = false)
        snap("app384_bottom_bar_$suffix.png")
    }

    /** (2) ⋯ overflow, split into two labelled groups. */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun captureOverflowGroups() {
        setOverlay(locked = false)
        runCatching {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("FILE ACTIONS").fetchSemanticsNodes().isNotEmpty()
            }
        }
        snap("app384_overflow_groups_$suffix.png")
    }

    /** (3) locked — the lock overlay's "Tap to unlock" pill over the bare video. */
    @Test
    fun captureLockedState() {
        compose.setContent {
            CalculatorVaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        VideoPlayerLockOverlay(onUnlock = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        snap("app384_locked_$suffix.png")
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun setSurface() {
        compose.setContent {
            CalculatorVaultTheme {
                Box(Modifier.fillMaxSize().testTag("surface").background(Color.Black)) {
                    VideoPlayerSurface(
                        player = CaptureInertPlayer(),
                        onToggleControls = {},
                        locked = false,
                        scale = 1f,
                        panX = 0f,
                        panY = 0f,
                        onPinch = { _, _, _ -> },
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                        rotationDegrees = 0,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** (4a) Brightness — LEFT-edge vertical swipe up, captured mid-gesture (pointer held). */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun captureGestureBrightness() {
        setSurface()
        runCatching {
            compose.onNodeWithTag("surface").performTouchInput {
                val x = width * 0.2f
                down(Offset(x, height * 0.8f))
                moveTo(Offset(x, height * 0.4f))
                moveTo(Offset(x, height * 0.3f))
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Brightness").fetchSemanticsNodes().isNotEmpty()
            }
            gestureSnap("app384_gesture_brightness_$suffix.png")
            compose.onNodeWithTag("surface").performTouchInput { up() }
        }
    }

    /** (4b) Volume — RIGHT-edge vertical swipe up. */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun captureGestureVolume() {
        setSurface()
        runCatching {
            compose.onNodeWithTag("surface").performTouchInput {
                val x = width * 0.8f
                down(Offset(x, height * 0.8f))
                moveTo(Offset(x, height * 0.4f))
                moveTo(Offset(x, height * 0.3f))
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Volume").fetchSemanticsNodes().isNotEmpty()
            }
            gestureSnap("app384_gesture_volume_$suffix.png")
            compose.onNodeWithTag("surface").performTouchInput { up() }
        }
    }

    /** (4c) Seek — horizontal swipe (centered time/total scrub card). */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun captureGestureSeek() {
        setSurface()
        runCatching {
            compose.onNodeWithTag("surface").performTouchInput {
                val y = height * 0.5f
                down(Offset(width * 0.3f, y))
                moveTo(Offset(width * 0.7f, y))
                moveTo(Offset(width * 0.8f, y))
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(" / ", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            gestureSnap("app384_gesture_seek_$suffix.png")
            compose.onNodeWithTag("surface").performTouchInput { up() }
        }
    }
}

/** Inert single-item player for the capture harness (no real decode). */
@UnstableApi
private class CaptureInertPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private val item =
        MediaItemData
            .Builder("capture-fixture")
            .setDurationUs(120_000_000L)
            .build()

    override fun getState(): State =
        State
            .Builder()
            .setAvailableCommands(
                Player.Commands
                    .Builder()
                    .addAllCommands()
                    .build(),
            ).setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(45_000L)
            .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
}
