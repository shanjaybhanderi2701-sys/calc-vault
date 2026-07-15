package com.appblish.calculatorvault.vault.viewer

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.playerkit.VideoScaleMath
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalcVault Phase B · APP-384 DoD — the MX-Player UI redesign of the in-vault video player. These
 * drive the *real* [VideoPlayerControlsOverlay] / [VideoPlayerLockOverlay] surfaces with an inert
 * player (no decode) and assert the owner's required arrangement:
 *
 *  1. The ⋯ overflow splits into two clearly-labelled groups — **Playback settings** (Subtitles /
 *     Audio track / Aspect ratio / Playback speed) and **File actions** (Share / Move / Unhide /
 *     Delete / Info).
 *  3. A clean bottom bar carries the whole control row (play/pause · prev/next · lock · rotate ·
 *     aspect · speed) — the redundant ExoPlayer center cluster is gone (built-in controller off).
 *  5. Full-immersive lock: `locked = true` hides ALL of the player chrome, and the lock overlay
 *     surfaces only a "Tap to unlock" affordance.
 */
@RunWith(AndroidJUnit4::class)
class MxPlayerRedesignDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private fun items() =
        listOf(
            VaultItem("a", VaultCategory.VIDEOS, "First.mp4", "Today", 0L, durationMs = 65_000L),
            VaultItem("b", VaultCategory.VIDEOS, "Second.mp4", "Today", 1L, durationMs = 5_000L),
        )

    private fun controller() =
        VideoPlaylistController(
            items = items(),
            currentIndex = 0,
            orderMode = OrderMode.ORDER,
            onOrderModeChanged = {},
            onSelect = {},
            onNext = {},
            onPrevious = {},
            onCompleted = {},
        )

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun overlay(locked: Boolean) {
        compose.setContent {
            CalculatorVaultTheme {
                VideoPlayerControlsOverlay(
                    player = InertRedesignPlayer(),
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
        compose.waitForIdle()
    }

    /** #3 — the clean bottom control row carries the full transport + tools set. */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun bottomBar_carriesTransportAndTools() {
        overlay(locked = false)
        for (desc in listOf("Previous", "Next", "Lock controls", "Rotate", "Aspect ratio", "Playback speed")) {
            assertThat(compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes()).isNotEmpty()
        }
        // A play/pause affordance is present (paused inert player → "Play").
        assertThat(compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes()).isNotEmpty()
    }

    /** #1 — the ⋯ overflow shows two clearly-separated groups with headers. */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun overflowMenu_splitsPlaybackAndFileGroups() {
        overlay(locked = false)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.waitForIdle()

        // Group (a) — Playback settings header + all four entries.
        compose.onNodeWithText("PLAYBACK SETTINGS").assertIsDisplayed()
        for (label in listOf("Subtitles", "Audio track", "Aspect ratio", "Playback speed")) {
            assertThat(compose.onAllNodesWithText(label).fetchSemanticsNodes()).isNotEmpty()
        }

        // Group (b) — File actions header + all five entries, visually separated.
        compose.onNodeWithText("FILE ACTIONS").assertIsDisplayed()
        for (label in listOf("Share", "Move", "Unhide", "Delete", "Info")) {
            assertThat(compose.onAllNodesWithText(label).fetchSemanticsNodes()).isNotEmpty()
        }
    }

    /**
     * APP-388 #2/#3 — the top-bar playlist affordance opens the playlist sheet, whose rows carry
     * the video title, its formatted duration, and a now-playing / play badge per row.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun playlistSheet_rowsShowTitleDurationAndBadge() {
        overlay(locked = false)
        // #3 — the modern top-bar playlist glyph is the labelled "Playlist" affordance.
        compose.onNodeWithContentDescription("Playlist").performClick()
        compose.waitForIdle()

        // Each current-folder video renders as a row: title + formatted duration.
        for (title in listOf("First.mp4", "Second.mp4")) {
            assertThat(compose.onAllNodesWithText(title).fetchSemanticsNodes()).isNotEmpty()
        }
        assertThat(compose.onAllNodesWithText("1:05").fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodesWithText("0:05").fetchSemanticsNodes()).isNotEmpty()
        // The current row (index 0) carries the now-playing badge on its thumbnail tile.
        assertThat(
            compose.onAllNodesWithContentDescription("Now playing").fetchSemanticsNodes(),
        ).isNotEmpty()
    }

    /** #5 — locked hides ALL controls; the overlay renders no chrome. */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun locked_hidesAllControls() {
        overlay(locked = true)
        for (desc in listOf("Previous", "Next", "Lock controls", "Rotate", "More options", "Mini player")) {
            assertThat(compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes()).isEmpty()
        }
    }

    /** #5 — the lock overlay surfaces only a "Tap to unlock" affordance. */
    @Test
    fun lockOverlay_showsUnlockAffordance() {
        var unlocked = false
        compose.setContent {
            CalculatorVaultTheme {
                VideoPlayerLockOverlay(onUnlock = { unlocked = true })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Tap to unlock").assertIsDisplayed()
        compose.onNodeWithText("Tap to unlock").performClick()
        assertThat(unlocked).isTrue()
    }
}

/** Minimal, inert single-item player for the redesign overlay tests (no real decode). */
@UnstableApi
private class InertRedesignPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private val item =
        MediaItemData
            .Builder("redesign-fixture")
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
            .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
}
