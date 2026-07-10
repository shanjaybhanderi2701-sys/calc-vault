package com.appblish.calculatorvault.vault.viewer

import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.DoDTestSupport
import com.appblish.calculatorvault.vault.EncryptedVaultContentRepository
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * APP-347 W1-ENG DoD (spec §8/§9, board Phase-1 check #2 video half): the pager's video
 * page now **streams** the encrypted vault blob through the seekable decrypting
 * [EncryptedVaultDataSource] — the exact wiring `PagerViewerScreen.MediaPlayerPage` uses —
 * with **no plaintext temp file**. A synthesized MP4 is hidden into the vault, played to
 * `STATE_READY`, then **seeked** (arbitrary-offset decrypt), and the run asserts the viewer
 * cache dir stays empty (§1.1: no plaintext on disk).
 *
 * The 1 GB+ smooth-playback + no-OOM latency case is the on-device W1-QA gate (APP-348).
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val namespace = "dod_video"
    private val videoName = "calcvault_dod_video_${System.nanoTime()}.mp4"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        DoDTestSupport.deleteVideoRows(context, videoName)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun encryptedVaultVideoStreamsAndSeeksViaDataSourceWithNoPlaintextOnDisk() =
        runBlocking<Unit> {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            val uri =
                DoDTestSupport.insertPublicVideo(
                    context,
                    videoName,
                    "Movies/CalcVaultDoD/",
                    DoDTestSupport.synthesizeMp4Bytes(context),
                )
            val stored =
                repo
                    .hide(
                        listOf(
                            VaultItem(
                                id = "staged-video",
                                category = VaultCategory.VIDEOS,
                                originalName = videoName,
                                dateLabel = "Today",
                                sortKey = System.currentTimeMillis(),
                                sourceUri = uri.toString(),
                                mimeType = "video/mp4",
                                relativePath = "Movies/CalcVaultDoD/",
                            ),
                        ),
                    ).single()

            val viewerCacheDir = File(context.cacheDir, "viewer")

            var player: ExoPlayer? = null
            try {
                instrumentation.runOnMainSync {
                    val dataSourceFactory =
                        EncryptedVaultDataSource.Factory { id -> repo.openBlobReader(id) }
                    val mediaSource =
                        ProgressiveMediaSource
                            .Factory(dataSourceFactory)
                            .createMediaSource(
                                MediaItem.fromUri(EncryptedVaultDataSource.vaultMediaUri(stored.id)),
                            )
                    player =
                        ExoPlayer.Builder(context).build().apply {
                            setMediaSource(mediaSource)
                            prepare()
                            playWhenReady = true
                        }
                }

                // Reaches READY + plays with a real duration — the encrypted blob streamed
                // through the DataSource is a container Media3 actually decodes.
                assertThat(awaitPlaying(player!!)).isTrue()
                var duration = 0L
                instrumentation.runOnMainSync { duration = player!!.duration }
                assertThat(duration).isGreaterThan(0)

                // Arbitrary-offset SEEK (§1.2): ExoPlayer reopens the DataSource at the target
                // byte offset; the reader decrypts just the needed chunk. Must resume playing.
                instrumentation.runOnMainSync { player!!.seekTo(duration / 2) }
                assertThat(awaitPlaying(player!!)).isTrue()

                // §1.1: streaming wrote NO plaintext temp file — the viewer cache stays empty.
                val leaked = viewerCacheDir.listFiles()?.toList().orEmpty()
                assertThat(leaked).isEmpty()
            } finally {
                instrumentation.runOnMainSync { player?.release() }
            }
        }

    /** Poll (on the main thread) until the player is playing/ended with content, or time out. */
    private fun awaitPlaying(player: ExoPlayer): Boolean {
        val deadline = System.currentTimeMillis() + 15_000
        var ok = false
        while (System.currentTimeMillis() < deadline && !ok) {
            instrumentation.runOnMainSync {
                val ready =
                    player.playbackState == Player.STATE_READY ||
                        player.playbackState == Player.STATE_ENDED
                val playing = player.isPlaying || player.playbackState == Player.STATE_ENDED
                ok = ready && playing
            }
            if (!ok) Thread.sleep(100)
        }
        return ok
    }
}
