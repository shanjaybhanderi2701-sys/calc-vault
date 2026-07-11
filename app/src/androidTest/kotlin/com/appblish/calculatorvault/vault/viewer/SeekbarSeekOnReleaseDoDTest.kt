package com.appblish.calculatorvault.vault.viewer

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalcVault Phase B · APP-429 (P0, APP-417 round 6) — the **seek-on-release-only** on-device gate.
 *
 * Owner device-rejection (round 6, verbatim): *"the seekbar currently performs a real seek (decrypt
 * from offset) on every finger movement during the drag … that is the freezing/sticking."* The
 * required behaviour: while the finger moves, update **only** the thumb + time label (pure UI, no
 * player call); on release, perform **exactly one** seek to the final position.
 *
 * This drives the **real production seekbar wiring** on-device — the actual [ThinSeekbar] gesture
 * loop feeding the actual [SeekbarScrubState], whose `commitSeek` calls `player.seekTo` exactly as
 * [VideoPlayerControlsOverlay] wires it — against a deterministic counting [Player]. It presses,
 * moves the finger across the bar several times **while still down**, and asserts:
 *  - **zero** seeks were issued during all the moves (the freeze cause is gone), then
 *  - on release, **exactly one** seek fired, at the **final** (right-edge) position, and
 *  - the player's play/pause state is untouched by the seek.
 *
 * A regression that re-introduced a per-move seek would make [CountingSeekPlayer.seekCount] > 0
 * mid-drag and fail here immediately — the automated device proof missing across the five rounds.
 */
@RunWith(AndroidJUnit4::class)
class SeekbarSeekOnReleaseDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val seekbarTag = "seekonrelease_seekbar"
    private val durationMs = 120_000L

    /** Renders the exact production wiring: ThinSeekbar → SeekbarScrubState → player.seekTo. */
    private fun setContent(player: Player) {
        compose.setContent {
            CalculatorVaultTheme {
                Surface(color = Color.Black) {
                    // Same binding as VideoPlayerControlsOverlay: the ONE seek runs in commitSeek.
                    val scrub = remember(player) { SeekbarScrubState(commitSeek = { player.seekTo(it) }) }
                    val fraction = (if (scrub.scrubbing) scrub.scrubValueMs else 0f) / durationMs
                    ThinSeekbar(
                        fraction = fraction.coerceIn(0f, 1f),
                        onScrub = { scrub.onScrub(it, durationMs) },
                        onScrubFinished = { scrub.onScrubFinished(durationMs) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .testTag(seekbarTag),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun drag_noSeekDuringMove_thenExactlyOneSeekOnRelease() {
        val player = CountingSeekPlayer(durationMs, initialPositionMs = 0L, playing = true)
        setContent(player)
        assertThat(player.playWhenReadyNow).isTrue() // precondition: playing

        val node = compose.onNodeWithTag(seekbarTag)

        // Press near the left and drag across the bar in several steps — finger STAYS DOWN.
        node.performTouchInput {
            down(Offset(width * 0.08f, centerY))
            moveTo(Offset(width * 0.30f, centerY))
            moveTo(Offset(width * 0.55f, centerY))
            moveTo(Offset(width * 0.80f, centerY))
            moveTo(Offset(width * 0.95f, centerY))
        }
        compose.waitForIdle()

        // THE fix: not a single seek may have fired while the finger was moving.
        assertThat(player.seekCount).isEqualTo(0)

        // Release — now exactly one seek, to the final (right-edge) position.
        node.performTouchInput { up() }
        compose.waitForIdle()

        assertThat(player.seekCount).isEqualTo(1)
        // Final position is near the right edge of the bar (well past the start).
        assertThat(player.contentPos).isGreaterThan((durationMs * 0.7f).toLong())
        assertThat(player.contentPos).isAtMost(durationMs)
        // Play/pause state preserved across the seek.
        assertThat(player.playWhenReadyNow).isTrue()
    }

    @Test
    fun tap_firesExactlyOneSeek() {
        val player = CountingSeekPlayer(durationMs, initialPositionMs = 0L, playing = false)
        setContent(player)

        // A plain tap on the bar (grab + release, no move) is a tap-to-jump: still exactly one seek.
        compose.onNodeWithTag(seekbarTag).performTouchInput {
            down(Offset(width * 0.5f, centerY))
            up()
        }
        compose.waitForIdle()

        assertThat(player.seekCount).isEqualTo(1)
        // Paused stays paused through the seek.
        assertThat(player.playWhenReadyNow).isFalse()
    }
}

/**
 * Deterministic player that counts `seekTo` calls: its playhead only moves — and [seekCount] only
 * increments — when the seekbar issues a real seek. [playWhenReadyNow] is fixed at construction and
 * never changed by a seek, so the tests can assert the play/pause state survives the seek.
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
            .Builder("scrub-fixture")
            .setDurationUs(durationMs * 1000L)
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
