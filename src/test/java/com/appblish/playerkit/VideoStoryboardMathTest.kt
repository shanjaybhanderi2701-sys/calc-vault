package com.appblish.playerkit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-419 — the pure scrub→frame mapping for the [VideoStoryboard] storyboard strip (no Android
 * types), so the arithmetic that drives the live scrub preview is unit-testable off-device: how
 * many frames a clip samples, which frame a drag position selects, and each frame's grab time.
 */
class VideoStoryboardMathTest {
    @Test
    fun frameCountScalesWithDurationWithinBounds() {
        // Unknown / zero duration → the minimum usable strip.
        assertThat(VideoStoryboard.frameCountFor(0L)).isEqualTo(VideoStoryboard.MIN_FRAMES)
        assertThat(VideoStoryboard.frameCountFor(-5L)).isEqualTo(VideoStoryboard.MIN_FRAMES)
        // A very short clip still gets at least MIN_FRAMES.
        assertThat(VideoStoryboard.frameCountFor(1_000L)).isEqualTo(VideoStoryboard.MIN_FRAMES)
        // A long clip is capped at MAX_FRAMES so the sheet stays small.
        assertThat(VideoStoryboard.frameCountFor(10 * 60 * 1000L)).isEqualTo(VideoStoryboard.MAX_FRAMES)
        // Every count is inside the declared bounds.
        longArrayOf(0, 500, 4_000, 20_000, 120_000, 3_600_000).forEach { d ->
            val n = VideoStoryboard.frameCountFor(d)
            assertThat(n).isAtLeast(VideoStoryboard.MIN_FRAMES)
            assertThat(n).isAtMost(VideoStoryboard.MAX_FRAMES)
        }
    }

    @Test
    fun frameIndexMapsFractionAcrossClosedInterval() {
        val count = 8
        // 0 → first frame, 1 → last frame (closed interval).
        assertThat(VideoStoryboard.frameIndexFor(0f, count)).isEqualTo(0)
        assertThat(VideoStoryboard.frameIndexFor(1f, count)).isEqualTo(count - 1)
        // Mid maps to the middle-ish frame and is monotonic.
        assertThat(VideoStoryboard.frameIndexFor(0.5f, count)).isEqualTo(4)
        // Out-of-range fractions clamp, never index out of bounds.
        assertThat(VideoStoryboard.frameIndexFor(-2f, count)).isEqualTo(0)
        assertThat(VideoStoryboard.frameIndexFor(9f, count)).isEqualTo(count - 1)
        // Degenerate strips are safe.
        assertThat(VideoStoryboard.frameIndexFor(0.7f, 1)).isEqualTo(0)
        assertThat(VideoStoryboard.frameIndexFor(0.7f, 0)).isEqualTo(0)
    }

    @Test
    fun frameTimesAreEvenlySpacedAndInRange() {
        val count = 5
        val durationMs = 20_000L
        val times = (0 until count).map { VideoStoryboard.frameTimeUs(it, count, durationMs) }
        // First at 0, last exactly at the end, all inside [0, duration].
        assertThat(times.first()).isEqualTo(0L)
        assertThat(times.last()).isEqualTo(durationMs * 1000L)
        times.forEach { t ->
            assertThat(t).isAtLeast(0L)
            assertThat(t).isAtMost(durationMs * 1000L)
        }
        // Strictly increasing (evenly spaced).
        times.zipWithNext().forEach { (a, b) -> assertThat(b).isGreaterThan(a) }
        // Degenerate inputs collapse to 0 rather than dividing by zero.
        assertThat(VideoStoryboard.frameTimeUs(0, 1, durationMs)).isEqualTo(0L)
        assertThat(VideoStoryboard.frameTimeUs(3, count, 0L)).isEqualTo(0L)
    }
}
