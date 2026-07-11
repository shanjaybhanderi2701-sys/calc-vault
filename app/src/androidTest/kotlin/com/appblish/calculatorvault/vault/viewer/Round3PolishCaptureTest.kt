package com.appblish.calculatorvault.vault.viewer

import android.graphics.Bitmap
import android.os.Environment
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.DoDTestSupport
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * CalcVault Phase B · APP-388 — on-device VISUAL capture of the round-3 video-player polish. Renders
 * the **real production** [VideoPlayerControlsOverlay] on the CI emulator (API 30/35) and writes PNGs
 * to `/sdcard/app388-evidence/` so CI can `adb pull` + upload them (same evidence path pattern the
 * APP-384 capture uses). Behaviour is *asserted* by [MxPlayerRedesignDoDTest]; this only *photographs*.
 *
 * The playlist rows are fed a synthetic [loadThumbnail] returning solid-colour tiles so the shot
 * shows genuine per-row image tiles (in production the folder-grid encrypted-thumbnail pipeline
 * supplies them). Every stage is best-effort (wrapped) so a capture hiccup never reddens the job.
 *
 * Shots (suffixed with the emulator API level):
 *   1. `app388_thin_seekbar_<api>.png`        — thin progress line + small round thumb, playing (#1).
 *   2. `app388_playlist_thumbnails_<api>.png` — playlist sheet with per-row thumbnails/duration (#2).
 *   3. `app388_playlist_icon_<api>.png`       — modern top-bar playlist glyph (#3).
 */
@RunWith(AndroidJUnit4::class)
class Round3PolishCaptureTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val suffix: String = "api${android.os.Build.VERSION.SDK_INT}"

    private fun outDir(): File {
        val ctx = instrumentation.targetContext
        runCatching { DoDTestSupport.grantAllFilesAccess(ctx) }
        return File(Environment.getExternalStorageDirectory(), "app388-evidence").also { it.mkdirs() }
    }

    private fun writePng(name: String) {
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(outDir(), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun snap(name: String) {
        runCatching {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            writePng(name)
        }
    }

    private fun fakeThumb(color: Int): ImageBitmap {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp.asImageBitmap()
    }

    private fun controller() =
        VideoPlaylistController(
            items =
                listOf(
                    VaultItem("a", VaultCategory.VIDEOS, "Beach sunset.mp4", "Today", 0L, durationMs = 65_000L),
                    VaultItem("b", VaultCategory.VIDEOS, "Road trip.mp4", "Today", 1L, durationMs = 132_000L),
                    VaultItem("c", VaultCategory.VIDEOS, "Birthday.mp4", "Today", 2L, durationMs = 5_000L),
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
    private fun setOverlay(playing: Boolean) {
        compose.setContent {
            CalculatorVaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        VideoPlayerControlsOverlay(
                            player = Round3InertPlayer(playing = playing),
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
                            playlist = controller(),
                            currentSubtitleLabel = null,
                            onLoadDeviceSubtitle = {},
                            onLoadVaultSubtitle = {},
                            onClearSubtitle = {},
                            onMinimize = {},
                            loadThumbnail = { item ->
                                fakeThumb(
                                    when (item.id) {
                                        "a" -> 0xFF3B6EA5.toInt()
                                        "b" -> 0xFF2E8B57.toInt()
                                        else -> 0xFF8B5A2E.toInt()
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** (1) thin seekbar — the slim progress line + small round thumb, playing state (#1). */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun captureThinSeekbar() {
        setOverlay(playing = true)
        snap("app388_thin_seekbar_$suffix.png")
    }

    /** (2) playlist sheet — per-row thumbnails + title + duration (#2). */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun capturePlaylistThumbnails() {
        setOverlay(playing = false)
        runCatching {
            compose.onNodeWithContentDescription("Playlist").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Beach sunset.mp4").fetchSemanticsNodes().isNotEmpty()
            }
        }
        snap("app388_playlist_thumbnails_$suffix.png")
    }

    /** (3) top-bar modern playlist glyph (#3). */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun capturePlaylistIcon() {
        setOverlay(playing = false)
        snap("app388_playlist_icon_$suffix.png")
    }
}

/** Inert single-item player for the APP-388 capture harness (no real decode). */
@UnstableApi
private class Round3InertPlayer(
    private val playing: Boolean
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val item =
        MediaItemData
            .Builder("app388-fixture")
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
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
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
