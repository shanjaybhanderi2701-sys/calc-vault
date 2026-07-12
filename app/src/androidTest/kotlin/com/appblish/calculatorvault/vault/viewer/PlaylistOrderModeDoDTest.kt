package com.appblish.calculatorvault.vault.viewer

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
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
 * CalcVault Phase B · APP-381 #3 DoD — the §5d **playlist + order modes** are present and
 * selectable in the real [VideoPlayerControlsOverlay]. Opening the playlist sheet surfaces the
 * current-folder videos and **all five** order modes (Order · Shuffle · Repeat Current · Loop
 * All · No Loop), and picking one drives the controller's `onOrderModeChanged`. The pure
 * order-mode arithmetic itself is JVM-tested in `PlaylistEngineTest`; this proves the UI wiring
 * on-device.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistOrderModeDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private fun vaultItem(
        id: String,
        name: String,
    ) = VaultItem(
        id = id,
        category = VaultCategory.VIDEOS,
        originalName = name,
        dateLabel = "Today",
        sortKey = 0L,
    )

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun playlistSheet_listsFolderVideos_andAllFiveOrderModes_selectable() {
        val items = listOf(vaultItem("a", "First.mp4"), vaultItem("b", "Second.mp4"))
        var selectedMode: OrderMode? = null
        val controller =
            VideoPlaylistController(
                items = items,
                currentIndex = 0,
                orderMode = OrderMode.ORDER,
                onOrderModeChanged = { selectedMode = it },
                onSelect = {},
                onNext = {},
                onPrevious = {},
                onCompleted = {},
            )

        compose.setContent {
            CalculatorVaultTheme {
                VideoPlayerControlsOverlay(
                    player = InertPlayer(),
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
                    locked = false,
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
                    playlist = controller,
                    currentSubtitleLabel = null,
                    onLoadDeviceSubtitle = {},
                    onLoadVaultSubtitle = {},
                    onClearSubtitle = {},
                    onMinimize = {},
                )
            }
        }
        compose.waitForIdle()

        // Open the playlist bottom sheet from the quick-control row.
        compose.onNodeWithContentDescription("Playlist").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Loop All").fetchSemanticsNodes().isNotEmpty()
        }

        // F1 — the current-folder videos are listed (tap-to-switch rows).
        assertThat(compose.onAllNodesWithText("First.mp4").fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodesWithText("Second.mp4").fetchSemanticsNodes()).isNotEmpty()

        // All five §5d order modes are present.
        for (label in listOf("Order", "Shuffle", "Repeat Current", "Loop All", "No Loop")) {
            assertThat(compose.onAllNodesWithText(label).fetchSemanticsNodes()).isNotEmpty()
        }

        // Selecting one drives the controller callback with the matching OrderMode.
        compose.onNodeWithText("Loop All").performClick()
        compose.waitForIdle()
        assertThat(selectedMode).isEqualTo(OrderMode.LOOP_ALL)
    }
}

/** Minimal, inert single-item player for the controls overlay (no real decode). */
@UnstableApi
private class InertPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private val item =
        MediaItemData
            .Builder("playlist-fixture")
            .setDurationUs(60_000_000L)
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
            .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
}
