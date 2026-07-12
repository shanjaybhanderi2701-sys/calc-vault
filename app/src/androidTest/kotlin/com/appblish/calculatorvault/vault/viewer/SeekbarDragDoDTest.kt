package com.appblish.calculatorvault.vault.viewer

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
 * APP-442 (R7 · APP-441 spec §3/§6) — the **one** seekbar gesture gate, replacing the eleven
 * overlapping seekbar/scrub DoD + capture tests that each asserted a narrow thing while the
 * assembled interaction stayed broken (six CI-green rounds, six device rejections).
 *
 * Per the spec this test does the thing the prior ones did NOT:
 *  - It drives the **REAL assembled player stack** — the actual [VideoPlayerSurface] +
 *    [VideoPlayerControlsOverlay] composed inside a swipeable [HorizontalPager], exactly as
 *    [PagerViewerScreen] layers them — NOT the seekbar composed in isolation with a fake.
 *  - It performs a **real multi-step drag** (~20 move events across the whole bar) on the thumb.
 *  - It asserts the [CountingSeekPlayer.seekCount] is **exactly 0 during the drag** and **exactly 1
 *    on release** (spec §1 cause #1 / §3), landing near the drop point.
 *  - It proves gesture ownership (spec §1 cause #6): the [HorizontalPager] is left **swipeable**
 *    (`userScrollEnabled = true`), yet a horizontal drag that starts on the seekbar must NOT page —
 *    the seekbar consumes the pointer on the Initial pass before the pager can claim it.
 *
 * The overlay runs an infinite ~2 Hz playhead poll (a `while (controlsVisible) { … delay }`), so the
 * test freezes the clock (`autoAdvance = false`) and steps frames manually; the poll and 3.5 s
 * auto-hide never fire within a single-frame step, and `waitForIdle()` (which would spin on the poll
 * loop) is avoided.
 */
@RunWith(AndroidJUnit4::class)
class SeekbarDragDoDTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val duration = 120_000L
    private lateinit var pagerState: PagerState

    /** Renders the production three-layer stack (surface + controls) inside a live 2-page pager. */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun setContent(player: Player) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CalculatorVaultTheme {
                pagerState = rememberPagerState(pageCount = { 2 })
                HorizontalPager(
                    state = pagerState,
                    // Deliberately swipeable: the seekbar must still own its own drag and win over
                    // the pager (spec §1 cause #6). Production locks this during playback; leaving it
                    // enabled here is the STRONGER assertion.
                    userScrollEnabled = true,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    if (page == 0) {
                        var controlsVisible by remember { mutableStateOf(true) }
                        Box(Modifier.fillMaxSize()) {
                            VideoPlayerSurface(
                                player = player,
                                onToggleControls = { controlsVisible = !controlsVisible },
                                locked = false,
                                scale = 1f,
                                panX = 0f,
                                panY = 0f,
                                onPinch = { _, _, _ -> },
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                rotationDegrees = 0,
                            )
                            VideoPlayerControlsOverlay(
                                player = player,
                                controlsVisible = controlsVisible,
                                onToggleControls = { controlsVisible = !controlsVisible },
                                fileActions = inertFileActions(),
                                locked = false,
                                onLockChanged = {},
                                speed = 1f,
                                onSpeedChanged = {},
                                aspectMode = VideoScaleMath.AspectMode.FIT,
                                onAspectModeChanged = {},
                                rotationDegrees = 0,
                                onRotationChanged = {},
                                muted = false,
                                onMutedChanged = {},
                                fullscreen = false,
                                onFullscreenChanged = {},
                                playlist = inertPlaylist(),
                                currentSubtitleLabel = null,
                                onLoadDeviceSubtitle = {},
                                onLoadVaultSubtitle = {},
                                onClearSubtitle = {},
                                onMinimize = {},
                                loadThumbnail = null,
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        // Compose + measure/layout without releasing the poll-loop delays.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun realDragOnAssembledStack_zeroSeeksDuringDrag_oneOnRelease_pagerNeverPages() {
        val player = CountingSeekPlayer(duration, initialPositionMs = 0L, playing = true)
        setContent(player)
        assertThat(player.playWhenReadyNow).isTrue()

        val bar = compose.onNodeWithTag(SEEK_BAR_TAG)

        // Press near the left and drag slowly across the whole bar in MANY steps, finger still down.
        bar.performTouchInput {
            down(Offset(width * 0.05f, centerY))
            val steps = 20
            for (i in 1..steps) {
                val f = 0.05f + (0.90f * i / steps)
                moveTo(Offset(width * f, centerY))
            }
        }
        compose.mainClock.advanceTimeByFrame()

        // THE fix (spec §1 #1): not one seek may have fired while the finger was moving.
        assertThat(player.seekCount).isEqualTo(0)
        // Gesture ownership (spec §1 #6): a drag begun on the seekbar must not have paged the pager.
        assertThat(pagerState.currentPage).isEqualTo(0)

        // Release → exactly ONE seek, to the final (right-edge) position.
        bar.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()

        assertThat(player.seekCount).isEqualTo(1)
        assertThat(player.contentPos).isGreaterThan((duration * 0.7f).toLong())
        assertThat(player.contentPos).isAtMost(duration)
        // Play/pause state preserved across the seek; pager still on the video page.
        assertThat(player.playWhenReadyNow).isTrue()
        assertThat(pagerState.currentPage).isEqualTo(0)
    }

    @Test
    fun tapOnBar_firesExactlyOneSeek() {
        val player = CountingSeekPlayer(duration, initialPositionMs = 0L, playing = false)
        setContent(player)

        compose.onNodeWithTag(SEEK_BAR_TAG).performTouchInput {
            down(Offset(width * 0.5f, centerY))
            up()
        }
        compose.mainClock.advanceTimeByFrame()

        assertThat(player.seekCount).isEqualTo(1)
        assertThat(player.playWhenReadyNow).isFalse()
    }
}

/**
 * Deterministic player that counts `seekTo`: its playhead only moves — and [seekCount] only
 * increments — when a real seek is issued. [playWhenReadyNow] is fixed at construction so the tests
 * can assert play/pause survives the seek.
 */
@UnstableApi
private class CountingSeekPlayer(
    durationMs: Long,
    initialPositionMs: Long,
    playing: Boolean,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    var contentPos: Long = initialPositionMs
        private set
    var seekCount: Int = 0
        private set
    var playWhenReadyNow: Boolean = playing
        private set

    private val item =
        MediaItemData
            .Builder("seekbar-fixture")
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
            .setPlayWhenReady(playWhenReadyNow, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(contentPos)
            .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        seekCount++
        contentPos = positionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
}

private fun inertFileActions() =
    ViewerFileActions(
        onBack = {},
        onInfo = {},
        onShare = {},
        onMove = {},
        onUnhide = {},
        onDelete = {},
    )

private fun inertPlaylist() =
    VideoPlaylistController(
        items = emptyList(),
        currentIndex = 0,
        orderMode = OrderMode.ORDER,
        onOrderModeChanged = {},
        onSelect = {},
        onNext = {},
        onPrevious = {},
        onCompleted = {},
    )
