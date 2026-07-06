package com.appblish.calculatorvault.vault.viewer

import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
 * Board-added Phase-1 check #2, video half (APP-237/APP-241): **video playback via
 * Media3/ExoPlayer.** A genuine synthesized MP4 is hidden into the encrypted vault, then
 * decrypted to the app-private viewer cache through the exact repository call the pager's
 * video page uses ([com.appblish.calculatorvault.vault.VaultContentRepository.decryptToFile],
 * see `PagerViewerViewModel.decryptToCache`) and handed to a real ExoPlayer with
 * `playWhenReady`, which must reach `STATE_READY` and start playing with a non-zero
 * duration — the blob round-trips the cipher into a container Media3 actually plays.
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

    @Test
    fun decryptedVaultVideoReachesReadyAndPlaysViaMedia3() =
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

            // The pager's video path: decrypt the blob to an extension-less private cache
            // temp file, then play that file.
            val target = File(File(context.cacheDir, "viewer"), "dod-playback-${System.nanoTime()}")
            target.parentFile?.mkdirs()
            try {
                assertThat(repo.decryptToFile(stored.id, target)).isTrue()
                assertThat(target.length()).isGreaterThan(0)

                var player: ExoPlayer? = null
                try {
                    instrumentation.runOnMainSync {
                        player =
                            ExoPlayer.Builder(context).build().apply {
                                setMediaItem(MediaItem.fromUri(Uri.fromFile(target)))
                                prepare()
                                playWhenReady = true
                            }
                    }
                    var ready = false
                    var playing = false
                    var duration = 0L
                    val deadline = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < deadline && !(ready && playing && duration > 0)) {
                        instrumentation.runOnMainSync {
                            val p = player!!
                            ready = ready ||
                                p.playbackState == Player.STATE_READY ||
                                p.playbackState == Player.STATE_ENDED
                            playing = playing || p.isPlaying || p.playbackState == Player.STATE_ENDED
                            if (p.duration > 0) duration = p.duration
                        }
                        Thread.sleep(100)
                    }
                    assertThat(ready).isTrue()
                    assertThat(playing).isTrue()
                    assertThat(duration).isGreaterThan(0)
                } finally {
                    instrumentation.runOnMainSync { player?.release() }
                }
            } finally {
                target.delete()
            }
        }
}
