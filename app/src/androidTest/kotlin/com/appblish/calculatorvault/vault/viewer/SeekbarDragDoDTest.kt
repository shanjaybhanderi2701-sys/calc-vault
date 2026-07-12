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
 * APP-448 adds [longDrag_whenPlaybackGoesInactiveMidDrag_stillSeeksOnce_pagerNeverPages], which wires
 * the stack the way [PagerViewerScreen] does (`userScrollEnabled = !playerActive && !seekbarDragging`,
 * plus `seekbarDragging` fed to [VideoPlayerSurface]) and flips `playerActive` FALSE mid-drag to
 * reproduce the long-video **buffering steal** — the failure the short-clip tests never triggered.
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

    /**
     * APP-448 — the **structural** regression that the isolated/short-clip tests missed for seven
     * rounds. This harness wires the stack exactly as [PagerViewerScreen] does in production:
     *  - `playerActive` gates the pager (starts TRUE = playing, so paging is locked as it is once a
     *    video plays), and `seekbarDragging` gates it too — `userScrollEnabled =
     *    !playerActive && !seekbarDragging`.
     *  - The **buffering steal** is simulated: the moment the seek drag starts, `playerActive` flips
     *    FALSE (a long video buffering mid-drag makes ExoPlayer report `isPlaying = false`). BEFORE
     *    the APP-448 fix that alone re-opened the pager → it stole the long horizontal drag →
     *    cancelled the seek pointer → `seekTo` never fired. The `!seekbarDragging` guard is what keeps
     *    the pager locked anyway, so the drag survives and seeks exactly once on release.
     *  - `seekbarDragging` is also handed to [VideoPlayerSurface] so its parallel scrub arbiter is
     *    off for the whole drag.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun setContentProductionWired(player: Player) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CalculatorVaultTheme {
                pagerState = rememberPagerState(pageCount = { 2 })
                // The hoisted signals, mirroring ViewerPager/MediaPlayerPage exactly.
                var seekbarDragging by remember { mutableStateOf(false) }
                var playerActive by remember { mutableStateOf(true) }
                HorizontalPager(
                    state = pagerState,
                    // Production gate: playback OR a seek drag locks paging. The drag flips
                    // playerActive false (buffering), so ONLY !seekbarDragging keeps it locked.
                    userScrollEnabled = !playerActive && !seekbarDragging,
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
                                seekbarDragging = seekbarDragging,
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
                                onSeekbarDraggingChanged = { dragging ->
                                    seekbarDragging = dragging
                                    // The steal: a long video buffers the instant the drag begins, so
                                    // ExoPlayer reports not-playing → playerActive false. Only the
                                    // seekbarDragging guard now keeps the pager from paging.
                                    if (dragging) playerActive = false
                                },
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun longDrag_whenPlaybackGoesInactiveMidDrag_stillSeeksOnce_pagerNeverPages() {
        // The exact reported failure: a long video, a full-width drag, buffering mid-drag. Asserts the
        // structural fix (spec #1): the pager stays locked via !seekbarDragging even though
        // playerActive flipped false, so the drag is never stolen and seeks exactly once on release.
        val player = CountingSeekPlayer(duration, initialPositionMs = 0L, playing = true)
        setContentProductionWired(player)

        val bar = compose.onNodeWithTag(SEEK_BAR_TAG)
        bar.performTouchInput {
            down(Offset(width * 0.03f, centerY))
            val steps = 30
            for (i in 1..steps) {
                moveTo(Offset(width * (0.03f + 0.94f * i / steps), centerY))
            }
        }
        compose.mainClock.advanceTimeByFrame()

        // No seek during the drag; the pager did NOT page despite playback going inactive mid-drag.
        assertThat(player.seekCount).isEqualTo(0)
        assertThat(pagerState.currentPage).isEqualTo(0)

        bar.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()

        // Release → exactly ONE seek near the right edge; the gesture ended as a real UP, not a Cancel.
        assertThat(player.seekCount).isEqualTo(1)
        assertThat(player.contentPos).isGreaterThan((duration * 0.7f).toLong())
        assertThat(player.contentPos).isAtMost(duration)
        assertThat(pagerState.currentPage).isEqualTo(0)
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
    fun canceledDrag_firesZeroSeeks() {
        // Spec §2 (APP-447): a pointer CANCEL — system/ancestor steals the gesture mid-drag — is NOT a
        // release, so no seek fires and the thumb snaps back to the player position.
        //
        // APP-448 — why this test is satisfiable where a naive `event.type == Release` guard made it
        // impossible: on Compose ui 1.7.6 the compose-ui-test `cancel()` DSL delivers a terminal
        // non-pressed change whose `event.type` is `Release` (identical to `up()`), BUT it arrives
        // already **consumed** (verified via logcat on emulator API 30/35: cancel → `isConsumed=true`,
        // up → `isConsumed=false`). The production guard is now `change.changedToUp()`
        // (`!isConsumed && !pressed && previousPressed`), which is false for the pre-consumed cancel and
        // true for a genuine up — so this test and `realDragOnAssembledStack…oneOnRelease` are both
        // satisfiable from the same DSL. It also matches the real steal: an ancestor that consumes the
        // pointer is exactly a consumed terminal change → no seek.
        val player = CountingSeekPlayer(duration, initialPositionMs = 0L, playing = true)
        setContent(player)

        compose.onNodeWithTag(SEEK_BAR_TAG).performTouchInput {
            down(Offset(width * 0.05f, centerY))
            moveTo(Offset(width * 0.50f, centerY))
            moveTo(Offset(width * 0.85f, centerY))
            cancel()
        }
        compose.mainClock.advanceTimeByFrame()

        // Cancel must not be mistaken for a release: zero seeks, playhead untouched, no page.
        assertThat(player.seekCount).isEqualTo(0)
        assertThat(player.contentPos).isEqualTo(0L)
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
