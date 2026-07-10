package com.appblish.calculatorvault.vault.viewer

import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
 * APP-348 W1-QA §9 / §6 DoD: an **unsupported / undecodable** vault "video" must surface a
 * graceful `onPlayerError` (mapped by `PagerViewerScreen.UnsupportedMediaPage` to
 * "This format isn't supported") — **never a crash**, never a silent black screen.
 *
 * Drives the exact production wiring ([EncryptedVaultDataSource] over a real hidden blob)
 * with a blob whose plaintext is NOT a decodable container, and asserts ExoPlayer reports a
 * container/format `PlaybackException` while the instrumentation process stays alive.
 */
@RunWith(AndroidJUnit4::class)
class UnsupportedFormatDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val namespace = "dod_unsupported"
    private val videoName = "calcvault_dod_bogus_${System.nanoTime()}.mp4"

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
    fun undecodableEncryptedVideoSurfacesGracefulErrorNeverCrashes() =
        runBlocking<Unit> {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            // A real hidden vault blob whose plaintext is bytes ExoPlayer cannot parse.
            val bogus = ByteArray(256 * 1024) { (it and 0x7F).toByte() }
            val uri = insertPublicVideoBytes(videoName, "Movies/CalcVaultDoD/", bogus)
            val stored =
                repo
                    .hide(
                        listOf(
                            VaultItem(
                                id = "staged-bogus",
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
            var error: PlaybackException? = null
            var player: ExoPlayer? = null
            try {
                instrumentation.runOnMainSync {
                    val factory = EncryptedVaultDataSource.Factory { id -> repo.openBlobReader(id) }
                    val source =
                        ProgressiveMediaSource
                            .Factory(factory)
                            .createMediaSource(MediaItem.fromUri(EncryptedVaultDataSource.vaultMediaUri(stored.id)))
                    player =
                        ExoPlayer.Builder(context).build().apply {
                            addListener(
                                object : Player.Listener {
                                    override fun onPlayerError(e: PlaybackException) {
                                        error = e
                                    }
                                },
                            )
                            setMediaSource(source)
                            prepare()
                            playWhenReady = true
                        }
                }

                // Wait for the graceful error signal (never a crash — the process is still alive).
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline && error == null) {
                    Thread.sleep(100)
                }

                assertThat(error).isNotNull()
                // The screen maps these parse/decode codes to "This format isn't supported" (§6).
                val gracefulCodes =
                    setOf(
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    )
                android.util.Log.i(
                    "UnsupportedFmtDoD",
                    "onPlayerError errorCode=${error!!.errorCode} (${error!!.errorCodeName}) — graceful, no crash",
                )
                assertThat(error!!.errorCode).isIn(gracefulCodes)

                // §1.1 — even a failed decode wrote no plaintext temp file.
                assertThat(viewerCacheDir.listFiles()?.toList().orEmpty()).isEmpty()
            } finally {
                instrumentation.runOnMainSync { player?.release() }
            }
        }

    private fun insertPublicVideoBytes(
        displayName: String,
        relativePath: String,
        bytes: ByteArray,
    ) = DoDTestSupport.insertPublicVideo(context, displayName, relativePath, bytes)
}
