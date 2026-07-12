package com.appblish.calculatorvault.vault.viewer

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.playerkit.VideoScaleMath
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalcVault Phase B · APP-381 DoD — captures the three on-device screenshots the ticket asks for
 * (**control bar**, **playlist sheet**, **mini player**) into the app's external files dir so CI
 * / a local run can pull them:
 *
 *   adb pull /sdcard/Android/data/com.appblish.calculatorvault/files/app381-screens
 *
 * These are the *real* Compose surfaces from the shipping player, driven with an inert fake
 * player (no decode), so the layout matches the owner design-reference control set exactly.
 */
@RunWith(AndroidJUnit4::class)
class PlayerControlsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Write into shared MediaStore (Pictures/app381) so the PNGs survive the post-test app
    // uninstall the connected-test task performs — then pull with:
    //   adb pull /sdcard/Pictures/app381
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // Let the compositor present the first frame before grabbing the device screen — the very
        // first capture can otherwise race the initial draw and grab a blank window.
        Thread.sleep(700)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val resolver = instrumentation.targetContext.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/app381")
            }
        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed for $name")
        resolver.openOutputStream(uri)!!.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun items() =
        listOf(
            VaultItem("a", VaultCategory.VIDEOS, "Beach clip.mp4", "Today", 0L),
            VaultItem("b", VaultCategory.VIDEOS, "Sunset.mp4", "Today", 1L),
            VaultItem("c", VaultCategory.VIDEOS, "Birthday.mp4", "Yesterday", 2L),
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
    private fun overlay(playlistController: VideoPlaylistController) {
        compose.setContent {
            CalculatorVaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black)) {
                        VideoPlayerControlsOverlay(
                            player = ScreenshotPlayer(),
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
                            playlist = playlistController,
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
    }

    @Test
    fun captureControlBar() {
        overlay(controller())
        capture("01-control-bar")
    }

    @Test
    fun capturePlaylistSheet() {
        overlay(controller())
        compose.onNodeWithContentDescription("Playlist").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Loop All").fetchSemanticsNodes().isNotEmpty()
        }
        capture("02-playlist-sheet")
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun captureMiniPlayer() {
        lateinit var session: MiniPlayerSession
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            session = MiniPlayerSession()
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            session.minimize(
                exoPlayer = player,
                itemId = "a",
                category = VaultCategory.VIDEOS,
                folderId = null,
                playlistVideoIds = listOf("a", "b", "c"),
                currentIndex = 0,
                order = OrderMode.ORDER,
            )
        }
        compose.setContent {
            CalculatorVaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    MiniPlayerWindow(session = session)
                }
            }
        }
        capture("03-mini-player")
        instrumentation.runOnMainSync { session.releaseAndClose() }
    }
}

/** Inert single-item player so the overlay renders its full control set without a real decode. */
@UnstableApi
private class ScreenshotPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private val item =
        MediaItemData
            .Builder("screenshot-fixture")
            .setDurationUs(180_000_000L)
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
            .setContentPositionMs(30_000L)
            .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
}
