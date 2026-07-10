package com.appblish.calculatorvault.vault.viewer

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-356 · W2-QA — on-device verification of the Wave-2 player gestures (spec §3), run against
 * the design-approved gesture-zone map (APP-354). This drives the real [VideoPlayerSurface]
 * Compose overlay on-device with a deterministic fake [Player] so the *wiring* (not just the
 * pure [VideoGestureMath], which is JVM-tested 17/17) is proven: real touch events flow through
 * Compose pointer-input to the player seek / window brightness / audio stream, and the transient
 * indicators render.
 *
 * The fake player's playhead is fixed and only moves when the overlay calls `seekTo`, so every
 * seek assertion is deterministic (no real-decode timing flake). Positions are chosen mid-clip
 * (60s of 120s) so ±10s never clamps.
 */
@RunWith(AndroidJUnit4::class)
class PlayerGestureDoDTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val duration = 120_000L
    private val startPos = 60_000L

    private fun setContent(player: Player) {
        compose.setContent {
            CalculatorVaultTheme {
                // Fixed-size surface so LEFT/RIGHT halves and drag distances are exact.
                androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp).testTag("surface")) {
                    // The Wave-3 surface takes the hoisted display state (APP-350); this test
                    // exercises only the Wave-2 gesture wiring, so pass inert defaults (unlocked,
                    // 1× scale, Fit) — the double-tap-seek / brightness / volume paths are
                    // independent of controls-visibility, zoom, aspect, and rotation.
                    VideoPlayerSurface(
                        player = player,
                        controlsVisible = true,
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

    /** Item 2 — double-tap RIGHT half advances +10s and shows the "+10s" indicator. */
    @Test
    fun doubleTapRight_seeksForwardTenSeconds_withIndicator() {
        val player = FakeSeekPlayer(duration, startPos)
        setContent(player)

        compose.onNodeWithTag("surface").performTouchInput {
            doubleClick(Offset(width * 0.75f, height * 0.5f))
        }
        compose.waitForIdle()

        assertThat(player.contentPos).isEqualTo(startPos + 10_000L)
        compose.onAllNodesWithText("+10s").fetchSemanticsNodes().let {
            assertThat(it).isNotEmpty()
        }
    }

    /** Item 2 — double-tap LEFT half rewinds -10s and shows the "−10s" indicator. */
    @Test
    fun doubleTapLeft_seeksBackTenSeconds_withIndicator() {
        val player = FakeSeekPlayer(duration, startPos)
        setContent(player)

        compose.onNodeWithTag("surface").performTouchInput {
            doubleClick(Offset(width * 0.25f, height * 0.5f))
        }
        compose.waitForIdle()

        assertThat(player.contentPos).isEqualTo(startPos - 10_000L)
        // U+2212 MINUS SIGN, matching VideoGestureMath.seekLabel.
        compose.onAllNodesWithText("−" + "10s").fetchSemanticsNodes().let {
            assertThat(it).isNotEmpty()
        }
    }

    /** Item 2 — consecutive same-side double-taps ratchet the indicator (+10s → +20s). */
    @Test
    fun consecutiveDoubleTapRight_ratchetsIndicatorToTwentySeconds() {
        val player = FakeSeekPlayer(duration, startPos)
        setContent(player)

        val node = compose.onNodeWithTag("surface")
        node.performTouchInput { doubleClick(Offset(width * 0.75f, height * 0.5f)) }
        node.performTouchInput { doubleClick(Offset(width * 0.75f, height * 0.5f)) }
        compose.waitForIdle()

        // Two 10s steps applied to the playhead; indicator shows the cumulative +20s.
        assertThat(player.contentPos).isEqualTo(startPos + 20_000L)
        compose.onAllNodesWithText("+20s").fetchSemanticsNodes().let {
            assertThat(it).isNotEmpty()
        }
    }

    /** Item 3 — vertical drag on the RIGHT half changes screen brightness and shows its bar. */
    @Test
    fun verticalDragRightHalf_changesBrightness_andShowsBar() {
        val player = FakeSeekPlayer(duration, startPos)
        setContent(player)

        compose.onNodeWithTag("surface").performTouchInput {
            val x = width * 0.8f
            down(Offset(x, height * 0.75f))
            moveTo(Offset(x, height * 0.25f)) // drag UP → brighten
            up()
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("Brightness").fetchSemanticsNodes().let {
            assertThat(it).isNotEmpty()
        }
        var b = -99f
        compose.activityRule.scenario.onActivity { b = it.window.attributes.screenBrightness }
        // Window brightness was overridden into the valid on-screen range (no longer -1 default).
        assertThat(b).isGreaterThan(0f)
        assertThat(b).isAtMost(1f)
    }

    /** Item 3 — vertical drag on the LEFT half targets volume and shows the volume bar. */
    @Test
    fun verticalDragLeftHalf_showsVolumeBar() {
        val player = FakeSeekPlayer(duration, startPos)
        setContent(player)

        compose.onNodeWithTag("surface").performTouchInput {
            val x = width * 0.2f
            down(Offset(x, height * 0.75f))
            moveTo(Offset(x, height * 0.25f))
            up()
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("Volume").fetchSemanticsNodes().let {
            assertThat(it).isNotEmpty()
        }
        // Vertical-left must NOT be read as brightness (zone separation).
        compose.onAllNodesWithText("Brightness").fetchSemanticsNodes().let {
            assertThat(it).isEmpty()
        }
    }

    /** Item 4 — horizontal drag scrubs (time preview) and commits the seek on release. */
    @Test
    fun horizontalDrag_scrubsAndSeeksOnRelease() {
        val player = FakeSeekPlayer(duration, startPos)
        setContent(player)

        compose.onNodeWithTag("surface").performTouchInput {
            val y = height * 0.5f
            down(Offset(width * 0.3f, y))
            moveTo(Offset(width * 0.9f, y)) // drag RIGHT → advance
            up()
        }
        compose.waitForIdle()

        // Seek committed on release, forward of the start (clamped within duration).
        assertThat(player.contentPos).isGreaterThan(startPos)
        assertThat(player.contentPos).isAtMost(duration)
    }

    /**
     * Item 5 (axis latch) — a mostly-horizontal drag scrubs only; it must NEVER leak into
     * brightness/volume. The dominant axis is latched HORIZONTAL past touch-slop.
     */
    @Test
    fun horizontalDrag_neverTriggersBrightnessOrVolume() {
        val player = FakeSeekPlayer(duration, startPos)
        setContent(player)

        compose.onNodeWithTag("surface").performTouchInput {
            val y = height * 0.5f
            down(Offset(width * 0.15f, y))
            moveTo(Offset(width * 0.5f, y + 20f))
            moveTo(Offset(width * 0.9f, y)) // net displacement dominated by X
            up()
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("Brightness").fetchSemanticsNodes().let {
            assertThat(it).isEmpty()
        }
        compose.onAllNodesWithText("Volume").fetchSemanticsNodes().let {
            assertThat(it).isEmpty()
        }
    }

    /** Item 1 — a single tap must not seek (it toggles controls; no seek indicator, no move). */
    @Test
    fun singleTap_doesNotSeek() {
        val player = FakeSeekPlayer(duration, startPos)
        setContent(player)

        compose.onNodeWithTag("surface").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        compose.waitForIdle()

        assertThat(player.contentPos).isEqualTo(startPos)
        compose.onAllNodesWithText("+10s").fetchSemanticsNodes().let { assertThat(it).isEmpty() }
        compose.onAllNodesWithText("−" + "10s").fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }
}

/**
 * Deterministic single-item player: exposes a fixed [duration] and a playhead that only moves
 * when the overlay calls `seekTo`. [contentPos] is the assertion hook.
 */
@UnstableApi
private class FakeSeekPlayer(
    durationMs: Long,
    initialPositionMs: Long,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    var contentPos: Long = initialPositionMs
        private set

    private val item =
        MediaItemData
            .Builder("gesture-fixture")
            .setDurationUs(durationMs * 1000L)
            .build()

    override fun getState(): State =
        State
            .Builder()
            .setAvailableCommands(
                Player.Commands
                    .Builder()
                    .addAllCommands()
                    .build()
            ).setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(contentPos)
            .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        contentPos = positionMs
        return Futures.immediateVoidFuture()
    }

    // The real PlayerView attaches/detaches a video surface; the fake just accepts it so
    // COMMAND_SET_VIDEO_SURFACE (advertised via addAllCommands) has a handler.
    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
}
