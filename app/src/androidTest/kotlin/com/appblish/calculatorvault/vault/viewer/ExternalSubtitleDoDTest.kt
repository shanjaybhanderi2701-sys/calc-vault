package com.appblish.calculatorvault.vault.viewer

import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
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
 * APP-371 (F4) Wave-3 DoD (spec §4, security sign-off APP-370 §10): the video player loads an
 * external subtitle **hidden in the vault** through the MANDATED `MergingMediaSource` +
 * `SingleSampleMediaSource` path — fed by the seekable decrypting [EncryptedVaultDataSource],
 * exactly the wiring `PagerViewerScreen.MediaPlayerPage.buildMediaSource` uses for a vault sub —
 * and the subtitle **syncs** (surfaces as a selectable TEXT track) with **no plaintext temp
 * file** (§10: never `MediaItem.setSubtitleConfigurations` on a progressive source, which would
 * stage a decrypted `.srt` on disk).
 *
 * F1–F3 (playlist list/tap-switch, Next/Prev, five order modes) are the pager-driven
 * [VideoPlaylistController] over the JVM-verified [PlaylistEngine] (44/44 cores) and are
 * device-verified through the UI in QA APP-368; this harness locks the one row whose failure
 * would breach the APP-370 no-plaintext-leak contract.
 */
@RunWith(AndroidJUnit4::class)
class ExternalSubtitleDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val namespace = "dod_subtitle"
    private val videoName = "calcvault_dod_subvid_${System.nanoTime()}.mp4"

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
    fun vaultHiddenSubtitleLoadsAndSyncsViaMergingSourceWithNoPlaintextOnDisk() =
        runBlocking<Unit> {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            // Hide a real MP4 video into the vault (streams through EncryptedVaultDataSource).
            val videoUri =
                DoDTestSupport.insertPublicVideo(
                    context,
                    videoName,
                    "Movies/CalcVaultDoD/",
                    DoDTestSupport.synthesizeMp4Bytes(context),
                )
            val video =
                repo
                    .hide(
                        listOf(
                            VaultItem(
                                id = "staged-video",
                                category = VaultCategory.VIDEOS,
                                originalName = videoName,
                                dateLabel = "Today",
                                sortKey = System.currentTimeMillis(),
                                sourceUri = videoUri.toString(),
                                mimeType = "video/mp4",
                                relativePath = "Movies/CalcVaultDoD/",
                            ),
                        ),
                    ).single()

            // Hide a real SubRip subtitle into the vault (its own encrypted blob).
            val srt =
                """
                1
                00:00:00,000 --> 00:00:01,000
                CalcVault DoD subtitle
                """.trimIndent().toByteArray()
            val srtFile = File.createTempFile("dod_sub", ".srt", context.cacheDir)
            srtFile.writeBytes(srt)
            val subtitle =
                repo
                    .hide(
                        listOf(
                            VaultItem(
                                id = "staged-sub",
                                category = VaultCategory.FILES,
                                originalName = "dod.srt",
                                dateLabel = "Today",
                                sortKey = System.currentTimeMillis(),
                                sourceUri = Uri.fromFile(srtFile).toString(),
                                mimeType = SubtitleFormats.MIME_SUBRIP,
                            ),
                        ),
                    ).single()
            srtFile.delete()

            val viewerCacheDir = File(context.cacheDir, "viewer")
            var player: ExoPlayer? = null
            val playerError = arrayOfNulls<androidx.media3.common.PlaybackException>(1)
            try {
                instrumentation.runOnMainSync {
                    // Exactly MediaPlayerPage.buildMediaSource for a vault-hidden sub: both the
                    // video and the subtitle stream through EncryptedVaultDataSource; the sub is
                    // MERGED, never handed to the progressive source as a SubtitleConfiguration.
                    val vaultFactory = EncryptedVaultDataSource.Factory { id -> repo.openBlobReader(id) }
                    val videoSource =
                        ProgressiveMediaSource
                            .Factory(vaultFactory)
                            .createMediaSource(MediaItem.fromUri(EncryptedVaultDataSource.vaultMediaUri(video.id)))
                    val subConfig =
                        MediaItem.SubtitleConfiguration
                            .Builder(EncryptedVaultDataSource.vaultMediaUri(subtitle.id))
                            .setMimeType(SubtitleFormats.MIME_SUBRIP)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    val subSource =
                        SingleSampleMediaSource
                            .Factory(vaultFactory)
                            .createMediaSource(subConfig, C.TIME_UNSET)
                    player =
                        ExoPlayer.Builder(context, legacySubtitleRenderersFactory(context)).build().apply {
                            setMediaSource(MergingMediaSource(videoSource, subSource))
                            addListener(
                                object : Player.Listener {
                                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                        playerError[0] = error
                                    }
                                },
                            )
                            prepare()
                            playWhenReady = true
                        }
                }

                // Reaches READY — the merged (video + vault-decrypted subtitle) source plays.
                val ready = awaitReady(player!!)
                if (!ready) {
                    val chain =
                        generateSequence(playerError[0] as Throwable?) { it.cause }
                            .joinToString(" -> ") { "${it.javaClass.simpleName}: ${it.message}" }
                    throw AssertionError("player never reached READY; error chain = $chain")
                }
                assertThat(ready).isTrue()

                // The subtitle SYNCED: a TEXT track group is now present in the player's tracks.
                var hasTextTrack = false
                instrumentation.runOnMainSync {
                    hasTextTrack = player!!.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
                }
                assertThat(hasTextTrack).isTrue()

                // §10: the vault sub streamed decrypted — NO plaintext `.srt` on disk.
                val leaked = viewerCacheDir.listFiles()?.toList().orEmpty()
                assertThat(leaked).isEmpty()
            } finally {
                instrumentation.runOnMainSync { player?.release() }
            }
        }

    /** Poll (on the main thread) until the player reaches READY/ENDED, or time out. */
    private fun awaitReady(player: ExoPlayer): Boolean {
        val deadline = System.currentTimeMillis() + 15_000
        var ok = false
        while (System.currentTimeMillis() < deadline && !ok) {
            instrumentation.runOnMainSync {
                ok = player.playbackState == Player.STATE_READY ||
                    player.playbackState == Player.STATE_ENDED
            }
            if (!ok) Thread.sleep(100)
        }
        return ok
    }
}
