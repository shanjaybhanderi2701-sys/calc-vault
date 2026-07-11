package com.appblish.calculatorvault.vault.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * CalcVault Phase B · APP-429 (P0, APP-417 round 6) — **seek-on-release-only** state for the video
 * seekbar.
 *
 * The owner device-rejected the seekbar five times for freezing / sticking mid-drag. Verbatim
 * (round 6): *"the seekbar currently performs a real seek (decrypt from offset) on every finger
 * movement during the drag … that is the freezing/sticking. This is the fix; it has been missed
 * five times."* The class of bug is: firing a real seek — which, for a vault video, decrypts from
 * the new byte offset — on **every** pointer-move (~30×/s), swamping the UI thread while the finger
 * moves.
 *
 * This holds a drag as **pure UI state**, which is the entire fix:
 *  - [onScrub] (grab + every pointer-move) updates ONLY [scrubbing] and [scrubValueMs] — the thumb
 *    position and the time label. It NEVER touches the player, so nothing seeks and nothing decrypts
 *    while the finger moves; the thumb therefore tracks the finger with zero lag.
 *  - [onScrubFinished] (release / drag-end) fires **exactly one** seek, to the final position, via
 *    [commitSeek]. That player call must run on the app (main) thread — ExoPlayer's contract — but it
 *    is non-blocking: ExoPlayer performs the actual decrypt-at-offset read on its internal loader
 *    thread, off the main thread ([EncryptedVaultDataSource] decrypts 512 KiB chunks there). A
 *    one-shot latch makes the seek exactly-once per drag, so a pointer-cancel-then-up (or any double
 *    drag-end) can never fire a second seek.
 *
 * The playing/paused state is preserved automatically: [commitSeek] only moves the playhead; it does
 * not call play()/pause(), and ExoPlayer keeps `playWhenReady` across a seek.
 *
 * Extracted into one [Stable] unit (instead of inline overlay state) so the seek-on-release contract
 * is locked by a single fast JVM test (`SeekbarScrubStateTest`) plus an on-device gesture test
 * (`SeekbarSeekOnReleaseDoDTest`) — the automated gate that was missing across the five prior rounds.
 */
@Stable
internal class SeekbarScrubState(
    private val commitSeek: (positionMs: Long) -> Unit,
) {
    /** True while a drag is in flight — the seekbar renders [scrubValueMs] instead of the playhead. */
    var scrubbing by mutableStateOf(false)
        private set

    /**
     * The live scrubbed position in ms (drives the thumb + the time label). Pure UI: it is NEVER sent
     * to the player mid-drag; only [onScrubFinished] hands the final value to [commitSeek].
     */
    var scrubValueMs by mutableFloatStateOf(0f)
        private set

    // Exactly-once latch: armed by grab/move, disarmed by the single commit on release.
    private var pendingCommit = false

    /**
     * Pointer grab or move: update ONLY the thumb + time label. No seek, no decrypt, no player call.
     * [fraction] is the touched position in `0f..1f`; [durationMs] maps it to a time on the clip.
     */
    fun onScrub(
        fraction: Float,
        durationMs: Long,
    ) {
        scrubbing = true
        pendingCommit = true
        scrubValueMs = fraction.coerceIn(0f, 1f) * durationMs.coerceAtLeast(0L)
    }

    /**
     * Release / drag-end: commit EXACTLY ONE seek to the final [scrubValueMs] (clamped to the clip),
     * then leave scrub mode. A no-op if no drag is pending — idempotent, so exactly-once per drag.
     */
    fun onScrubFinished(durationMs: Long) {
        if (!pendingCommit) return
        pendingCommit = false
        val target = scrubValueMs.toLong().coerceIn(0L, durationMs.coerceAtLeast(0L))
        scrubbing = false
        commitSeek(target)
    }
}
