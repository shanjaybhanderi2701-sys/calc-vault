package com.appblish.calculatorvault.vault.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * CalcVault Phase B · APP-381 #1 — keep a video's **playhead + play/pause state across an
 * activity recreation** (a forced `recreate()`, or a process-death restore), re-applying them to
 * a freshly built [player].
 *
 * Physical device rotation is already seamless without this: `MainActivity` declares
 * `android:configChanges="orientation|screenSize|…"`, so a real rotation never tears the
 * composition down — the *same* ExoPlayer keeps playing (no restart-from-zero, no re-decrypt of
 * the encrypted stream). This effect is the belt-and-suspenders path for the cases where the
 * composition genuinely is disposed and the player is rebuilt from zero.
 *
 * The saved value is read from the **live** player at save time (via the [Saver] `save` lambda,
 * which the `SaveableStateRegistry` invokes during `onSaveInstanceState`), so it is always the
 * up-to-the-moment playhead — no polling, no lifecycle observer, and deterministically testable
 * with `StateRestorationTester.emulateSavedInstanceStateRestore()`.
 *
 * On the very first entry there is nothing saved: the sentinel position `-1` means "do not
 * seek", so a fresh open (or a live player adopted from the mini-player session on Expand) is
 * never disturbed.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun RetainedPlaybackEffect(
    player: Player,
    itemId: String,
) {
    val restored =
        rememberSaveable(itemId, saver = playbackResumeSaver(player)) {
            // [positionMs, playWhenReady]; -1 position = nothing saved yet → skip restore.
            longArrayOf(-1L, 1L)
        }
    LaunchedEffect(player) {
        val positionMs = restored[0]
        if (positionMs >= 0L) {
            player.seekTo(positionMs)
            player.playWhenReady = restored[1] == 1L
        }
    }
}

/**
 * A [Saver] that snapshots the live [player] playhead + `playWhenReady` at registry-save time,
 * so the restored [LongArray] always reflects the exact position the video was at when the
 * activity was torn down (not a stale polled value).
 */
private fun playbackResumeSaver(player: Player): Saver<LongArray, LongArray> =
    Saver(
        save = { longArrayOf(player.currentPosition, if (player.playWhenReady) 1L else 0L) },
        restore = { it },
    )

/** Walk the [Context] wrapper chain to the hosting [Activity] (for window-level fullscreen). */
internal tailrec fun Context.findActivityOrNull(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivityOrNull()
        else -> null
    }
