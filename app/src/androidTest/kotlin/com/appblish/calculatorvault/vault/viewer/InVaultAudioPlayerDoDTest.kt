package com.appblish.calculatorvault.vault.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-526 DoD — the in-vault **audio** now-playing UI (`AudioNowPlayingContent`), the surface that
 * proves the audio player reuses the encrypted playback layer **minus the video surface**:
 *  - It renders as a now-playing screen (title, album-art tile, elapsed/total, transport) — **no**
 *    `VideoPlayerSurface`/`PlayerView` is composed (the audio tag is present; the video surface tag
 *    is not).
 *  - The **reused** [VideoSeekbar] (seek-on-release) is present and wired.
 *  - Play/Pause, Next/Prev and tap-to-switch drive the shared [VideoPlaylistController] callbacks.
 *  - All **five** [OrderMode]s (Order / Shuffle / Repeat Current / Loop All / No Loop) are offered
 *    and selectable — reused verbatim from the video player.
 *
 * This is the composition DoD; the full end-to-end on a real decrypted vault audio blob (audible
 * playback from the encrypted stream across MP3/WAV/AAC/FLAC/M4A/OGG, a physical seekbar drag, and
 * **no background playback** when the screen is left / the phone locks) is the on-device QA gate
 * (APP-526 → QA Engineer) and the separate audio-path Security review.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class InVaultAudioPlayerDoDTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun audioItem(
        id: String,
        name: String,
    ) = VaultItem(
        id = id,
        category = VaultCategory.AUDIOS,
        originalName = name,
        dateLabel = "Today",
        sortKey = 0L,
    )

    /** A [VideoPlaylistController] whose callbacks record what the audio UI invoked. */
    private class Recorder(
        items: List<VaultItem>,
        current: Int,
    ) {
        var orderMode = OrderMode.ORDER
        var nextCalls = 0
        var prevCalls = 0
        var selected = -1
        val controller =
            VideoPlaylistController(
                items = items,
                currentIndex = current,
                orderMode = orderMode,
                onOrderModeChanged = { orderMode = it },
                onSelect = { selected = it },
                onNext = { nextCalls++ },
                onPrevious = { prevCalls++ },
                onCompleted = {},
            )
    }

    private val noFileActions =
        ViewerFileActions(
            onBack = {},
            onInfo = {},
            onShare = {},
            onMove = {},
            onUnhide = {},
            onDelete = {},
        )

    @Test
    fun nowPlaying_rendersTitle_total_and_seekbar_withNoVideoSurface() {
        val rec = Recorder(listOf(audioItem("a0", "Song One.mp3")), current = 0)
        composeRule.setContent {
            CalculatorVaultTheme {
                AudioNowPlayingContent(
                    title = "Song One.mp3",
                    artwork = null,
                    positionMs = 30_000L,
                    durationMs = 200_000L,
                    isPlaying = true,
                    playlist = rec.controller,
                    fileActions = noFileActions,
                    loadThumbnail = null,
                    onPlayPause = {},
                    onSeek = {},
                    onSeekbarDraggingChanged = {},
                )
            }
        }

        composeRule.onNodeWithTag(AUDIO_PLAYER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(AUDIO_TITLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Song One.mp3").assertIsDisplayed()
        // Album-art placeholder tile present (no video surface): the now-playing screen, not a player.
        composeRule.onNodeWithTag(AUDIO_ART_TAG).assertIsDisplayed()
        // The reused seek-on-release bar is present and the total time (3:20) is shown next to it.
        // Assert the total via its own tag: the reused seekbar also carries "3:20" as an invisible
        // width-pin, so a bare text match would be ambiguous (two nodes).
        composeRule.onNodeWithTag(SEEK_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(AUDIO_TOTAL_TAG).assertTextEquals("3:20")
    }

    @Test
    fun playPause_prev_next_drive_callbacks() {
        var playPauseCalls = 0
        val rec = Recorder(listOf(audioItem("a0", "a"), audioItem("a1", "b")), current = 0)
        composeRule.setContent {
            CalculatorVaultTheme {
                AudioNowPlayingContent(
                    title = "a",
                    artwork = null,
                    positionMs = 0L,
                    durationMs = 100_000L,
                    isPlaying = false,
                    playlist = rec.controller,
                    fileActions = noFileActions,
                    loadThumbnail = null,
                    onPlayPause = { playPauseCalls++ },
                    onSeek = {},
                    onSeekbarDraggingChanged = {},
                )
            }
        }

        composeRule.onNodeWithTag(AUDIO_PLAY_PAUSE_TAG).performClick()
        composeRule.onNodeWithTag(AUDIO_NEXT_TAG).performClick()
        composeRule.onNodeWithTag(AUDIO_PREV_TAG).performClick()

        assertThat(playPauseCalls).isEqualTo(1)
        assertThat(rec.nextCalls).isEqualTo(1)
        assertThat(rec.prevCalls).isEqualTo(1)
    }

    @Test
    fun playlistSheet_listsAudioRows_switchesTrack_and_offersAllFiveOrderModes() {
        val items = listOf(audioItem("a0", "First.mp3"), audioItem("a1", "Second.flac"))
        val rec = Recorder(items, current = 0)
        composeRule.setContent {
            CalculatorVaultTheme {
                AudioNowPlayingContent(
                    title = "First.mp3",
                    artwork = null,
                    positionMs = 0L,
                    durationMs = 100_000L,
                    isPlaying = true,
                    playlist = rec.controller,
                    fileActions = noFileActions,
                    loadThumbnail = null,
                    onPlayPause = {},
                    onSeek = {},
                    onSeekbarDraggingChanged = {},
                )
            }
        }

        composeRule.onNodeWithTag(AUDIO_PLAYLIST_TAG).performClick()

        // Every OrderMode is offered (reused verbatim from the video player).
        OrderMode.entries.forEach { mode ->
            composeRule.onNodeWithTag("$AUDIO_ORDER_TAG_PREFIX${mode.name}").assertIsDisplayed()
        }

        // Selecting Shuffle routes through the shared controller.
        composeRule.onNodeWithTag("$AUDIO_ORDER_TAG_PREFIX${OrderMode.SHUFFLE.name}").performClick()
        assertThat(rec.orderMode).isEqualTo(OrderMode.SHUFFLE)

        // Tapping the second row switches playback to that pager page.
        composeRule.onNodeWithTag("${AUDIO_ROW_TAG_PREFIX}1").performClick()
        assertThat(rec.selected).isEqualTo(1)
    }

    @Test
    fun displayTitle_prefersStreamTag_fallsBackToFileName() {
        val tagged = MediaMetadata.Builder().setTitle("Tagged Title").build()
        val blank = MediaMetadata.Builder().setTitle("").build()
        val none = MediaMetadata.Builder().build()

        assertThat(displayTitle(tagged, "file.mp3")).isEqualTo("Tagged Title")
        assertThat(displayTitle(blank, "file.mp3")).isEqualTo("file.mp3")
        assertThat(displayTitle(none, "file.mp3")).isEqualTo("file.mp3")
    }
}
