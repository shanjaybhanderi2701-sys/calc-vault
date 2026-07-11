package com.appblish.calculatorvault.vault.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CalcVault Phase B · APP-429 (P0, APP-417 round 6) — the fast JVM gate for **seek-on-release only**.
 *
 * This locks the exact contract the owner has rejected five times for want of an automated proof:
 *  - dragging (any number of [SeekbarScrubState.onScrub] moves) fires **zero** seeks, and
 *  - releasing ([SeekbarScrubState.onScrubFinished]) fires **exactly one** seek at the **final**
 *    position, clamped to the clip, with the play/pause state untouched by the state object.
 *
 * The seek is recorded via the [SeekbarScrubState.commitSeek] callback, so this needs no player,
 * no Compose composition, and no emulator — it runs in milliseconds on CI's unit-test job and would
 * fail immediately if any future change re-introduced a per-move seek.
 */
class SeekbarScrubStateTest {
    private val durationMs = 120_000L

    @Test
    fun drag_moves_fireZeroSeeks() {
        val seeks = mutableListOf<Long>()
        val state = SeekbarScrubState(commitSeek = { seeks += it })

        // Grab, then a burst of pointer-moves across the bar — nothing may seek yet.
        state.onScrub(0.10f, durationMs)
        state.onScrub(0.35f, durationMs)
        state.onScrub(0.60f, durationMs)
        state.onScrub(0.90f, durationMs)

        assertEquals("no seek may fire during the drag", emptyList<Long>(), seeks)
        assertTrue("the bar must be in scrub mode while dragging", state.scrubbing)
        // The label tracks the finger live (pure UI), even though no seek fired.
        assertEquals(0.90f * durationMs, state.scrubValueMs, 1f)
    }

    @Test
    fun release_firesExactlyOneSeek_atFinalPosition() {
        val seeks = mutableListOf<Long>()
        val state = SeekbarScrubState(commitSeek = { seeks += it })

        state.onScrub(0.10f, durationMs)
        state.onScrub(0.50f, durationMs)
        state.onScrub(0.80f, durationMs) // final finger position before release
        state.onScrubFinished(durationMs)

        // Exactly one seek, and it lands on the FINAL scrub position — not any intermediate one.
        assertEquals(listOf((0.80f * durationMs).toLong()), seeks)
        assertFalse("scrub mode must clear on release", state.scrubbing)
    }

    @Test
    fun doubleRelease_stillFiresOnlyOneSeek() {
        val seeks = mutableListOf<Long>()
        val state = SeekbarScrubState(commitSeek = { seeks += it })

        state.onScrub(0.50f, durationMs)
        state.onScrubFinished(durationMs)
        // A pointer-cancel-then-up, or any double drag-end, must NOT fire a second seek.
        state.onScrubFinished(durationMs)

        assertEquals("exactly one seek per drag", 1, seeks.size)
    }

    @Test
    fun tapWithoutMove_firesOneSeekAtTouchPoint() {
        val seeks = mutableListOf<Long>()
        val state = SeekbarScrubState(commitSeek = { seeks += it })

        // A tap = grab (onScrub) then immediate release, no moves. Still one seek (tap-to-jump).
        state.onScrub(0.25f, durationMs)
        state.onScrubFinished(durationMs)

        assertEquals(listOf((0.25f * durationMs).toLong()), seeks)
    }

    @Test
    fun finalPosition_isClampedToClip() {
        val seeks = mutableListOf<Long>()
        val state = SeekbarScrubState(commitSeek = { seeks += it })

        // A fraction past the end (e.g. an over-travel touch) can never seek beyond the duration.
        state.onScrub(1.5f, durationMs)
        state.onScrubFinished(durationMs)

        assertEquals(listOf(durationMs), seeks)
    }
}
