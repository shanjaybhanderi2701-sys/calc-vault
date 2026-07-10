package com.appblish.calculatorvault.vault.viewer

import android.os.Looper
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalcVault Phase B · APP-381 #1 DoD — **rotation must not restart the video**. Physical device
 * rotation is handled seamlessly in-process via `MainActivity`'s `android:configChanges` (the
 * composition is never torn down), but a *forced* recreation (process-death restore, or a
 * `recreate()`) still disposes the composition and rebuilds the ExoPlayer from zero. This test
 * proves the [RetainedPlaybackEffect] safety net: after the composition is saved, disposed, and
 * recomposed with a **freshly built** player, the playhead and play/pause state are restored.
 *
 * [StateRestorationTester.emulateSavedInstanceStateRestore] performs exactly that save → dispose
 * → restore cycle, driving the same `SaveableStateRegistry` path the framework uses on a real
 * configuration-change/process-death recreation — so this is deterministic (no device rotation,
 * no real decode timing), which is the preferred CI-instrumented proof.
 */
@RunWith(AndroidJUnit4::class)
class RotationPreservesPositionDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val durationMs = 120_000L

    @Test
    fun forcedRecreation_restoresPlayheadAndPlayingState() {
        val tester = StateRestorationTester(compose)
        lateinit var current: RecordingPlayer
        tester.setContent {
            // A NEW player on each (re)composition — mirrors the real ExoPlayer being rebuilt
            // from position 0 when the activity is recreated.
            val player = remember { RecordingPlayer(durationMs).also { current = it } }
            RetainedPlaybackEffect(player = player, itemId = "clip-1")
        }
        compose.waitForIdle()

        // Playback has advanced to 42s and is running. Player state must be touched on the
        // application (main) thread — SimpleBasePlayer enforces thread affinity.
        val before = current
        compose.runOnUiThread { before.moveToForTest(42_000L, playing = true) }
        compose.waitForIdle()

        // Rotate/recreate: save → dispose → recompose with a fresh player at 0.
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        // A brand-new player instance was built, then restored in place — NOT restarted at 0.
        assertThat(current).isNotSameInstanceAs(before)
        val restoredPos = compose.runOnUiThread { current.contentPos }
        val restoredPlaying = compose.runOnUiThread { current.playWhenReadyValue }
        assertThat(restoredPos).isEqualTo(42_000L)
        assertThat(restoredPlaying).isTrue()
    }

    @Test
    fun forcedRecreation_preservesPausedState() {
        val tester = StateRestorationTester(compose)
        lateinit var current: RecordingPlayer
        tester.setContent {
            val player = remember { RecordingPlayer(durationMs).also { current = it } }
            RetainedPlaybackEffect(player = player, itemId = "clip-2")
        }
        compose.waitForIdle()

        val before = current
        compose.runOnUiThread { before.moveToForTest(30_000L, playing = false) }
        compose.waitForIdle()

        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertThat(current).isNotSameInstanceAs(before)
        val restoredPos = compose.runOnUiThread { current.contentPos }
        val restoredPlaying = compose.runOnUiThread { current.playWhenReadyValue }
        assertThat(restoredPos).isEqualTo(30_000L)
        // A video paused before rotation stays paused after — play/pause is preserved too.
        assertThat(restoredPlaying).isFalse()
    }
}

/**
 * Deterministic fake player: a fixed playhead + play/pause that only change via [moveToForTest]
 * or the effect's `seekTo`/`playWhenReady`. Playback is kept **suppressed** so `isPlaying` is
 * false and [SimpleBasePlayer] never extrapolates the (constant) position by wall-clock — every
 * assertion is exact.
 */
@UnstableApi
private class RecordingPlayer(
    durationMs: Long,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var pos = 0L
    private var pwr = false

    val contentPos: Long get() = pos
    val playWhenReadyValue: Boolean get() = pwr

    private val item =
        MediaItemData
            .Builder("retain-fixture")
            .setDurationUs(durationMs * 1000L)
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
            .setPlayWhenReady(pwr, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            // Freeze the reported position: suppression keeps isPlaying == false so the constant
            // position supplier is never wrapped in wall-clock extrapolation.
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(pos)
            .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        pos = positionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        pwr = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** Test-only: place the playhead and play/pause as if playback had progressed there. */
    fun moveToForTest(
        positionMs: Long,
        playing: Boolean,
    ) {
        pos = positionMs
        pwr = playing
        invalidateState()
    }
}
