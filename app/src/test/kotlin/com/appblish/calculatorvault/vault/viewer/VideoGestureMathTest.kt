package com.appblish.calculatorvault.vault.viewer

import com.appblish.calculatorvault.vault.viewer.VideoGestureMath.Axis
import com.appblish.calculatorvault.vault.viewer.VideoGestureMath.Zone
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-349 (Wave 2) — the gesture-zone map and per-gesture math for the in-vault video player
 * (spec §3), unit-tested on the JVM so the "gestures must not misfire" gate is proven by the
 * rules, not only by on-device feel. Every branch of [VideoGestureMath] is pinned here.
 */
class VideoGestureMathTest {
    // ---- zones (spec §3: left/right split at the midpoint) ----

    @Test
    fun `left of centre is the LEFT zone, midpoint and beyond is RIGHT`() {
        assertThat(VideoGestureMath.zoneFor(0f, 1000f)).isEqualTo(Zone.LEFT)
        assertThat(VideoGestureMath.zoneFor(499f, 1000f)).isEqualTo(Zone.LEFT)
        assertThat(VideoGestureMath.zoneFor(500f, 1000f)).isEqualTo(Zone.RIGHT)
        assertThat(VideoGestureMath.zoneFor(999f, 1000f)).isEqualTo(Zone.RIGHT)
    }

    @Test
    fun `zero width does not divide by zero`() {
        assertThat(VideoGestureMath.zoneFor(0f, 0f)).isEqualTo(Zone.LEFT)
    }

    // ---- axis latch: a horizontal scrub must never leak into brightness/volume ----

    @Test
    fun `dominant axis follows the larger component`() {
        assertThat(VideoGestureMath.dominantAxis(100f, 10f)).isEqualTo(Axis.HORIZONTAL)
        assertThat(VideoGestureMath.dominantAxis(-120f, 30f)).isEqualTo(Axis.HORIZONTAL)
        assertThat(VideoGestureMath.dominantAxis(10f, 100f)).isEqualTo(Axis.VERTICAL)
        assertThat(VideoGestureMath.dominantAxis(20f, -140f)).isEqualTo(Axis.VERTICAL)
    }

    @Test
    fun `an exact diagonal tie resolves to horizontal scrub`() {
        assertThat(VideoGestureMath.dominantAxis(50f, 50f)).isEqualTo(Axis.HORIZONTAL)
        assertThat(VideoGestureMath.dominantAxis(0f, 0f)).isEqualTo(Axis.HORIZONTAL)
    }

    // ---- double-tap seek: side sign + ratcheting accumulation ----

    @Test
    fun `right double-tap seeks forward, left seeks back, by 10s`() {
        assertThat(VideoGestureMath.seekDeltaMs(Zone.RIGHT, 1)).isEqualTo(10_000L)
        assertThat(VideoGestureMath.seekDeltaMs(Zone.LEFT, 1)).isEqualTo(-10_000L)
    }

    @Test
    fun `consecutive double-taps on a side ratchet the magnitude`() {
        assertThat(VideoGestureMath.seekDeltaMs(Zone.RIGHT, 2)).isEqualTo(20_000L)
        assertThat(VideoGestureMath.seekDeltaMs(Zone.RIGHT, 3)).isEqualTo(30_000L)
        assertThat(VideoGestureMath.seekDeltaMs(Zone.LEFT, 4)).isEqualTo(-40_000L)
    }

    @Test
    fun `a non-positive tap count is treated as one step`() {
        assertThat(VideoGestureMath.seekDeltaMs(Zone.RIGHT, 0)).isEqualTo(10_000L)
    }

    // ---- seek clamps to the clip bounds ----

    @Test
    fun `seekTo clamps at the ends`() {
        assertThat(VideoGestureMath.seekTo(5_000L, -10_000L, 60_000L)).isEqualTo(0L)
        assertThat(VideoGestureMath.seekTo(55_000L, 10_000L, 60_000L)).isEqualTo(60_000L)
        assertThat(VideoGestureMath.seekTo(30_000L, 10_000L, 60_000L)).isEqualTo(40_000L)
    }

    // ---- horizontal scrub → absolute target with time preview ----

    @Test
    fun `a full-width right drag advances by the scrub span`() {
        // startMs 0, drag one full width right over a 5-minute clip.
        val target = VideoGestureMath.scrubTargetMs(0L, 1000f, 1000f, 300_000L)
        assertThat(target).isEqualTo(VideoGestureMath.SCRUB_FULL_WIDTH_MS)
    }

    @Test
    fun `a left drag rewinds and clamps at zero`() {
        val target = VideoGestureMath.scrubTargetMs(10_000L, -1000f, 1000f, 300_000L)
        assertThat(target).isEqualTo(0L)
    }

    @Test
    fun `scrub never exceeds the duration`() {
        val target = VideoGestureMath.scrubTargetMs(295_000L, 1000f, 1000f, 300_000L)
        assertThat(target).isEqualTo(300_000L)
    }

    @Test
    fun `zero-width surface leaves the playhead put`() {
        assertThat(VideoGestureMath.scrubTargetMs(42_000L, 500f, 0f, 300_000L)).isEqualTo(42_000L)
    }

    // ---- brightness / volume vertical drags (up = increase) ----

    @Test
    fun `dragging up brightens, down darkens, clamped to 1percent-100percent`() {
        // Drag up half the surface height → +0.5.
        assertThat(VideoGestureMath.adjustBrightness(0.4f, -500f, 1000f)).isWithin(1e-4f).of(0.9f)
        // Overshoot up clamps at 1.0.
        assertThat(VideoGestureMath.adjustBrightness(0.8f, -500f, 1000f)).isEqualTo(1.0f)
        // Overshoot down clamps at the 1% floor, never fully black.
        assertThat(VideoGestureMath.adjustBrightness(0.2f, 1000f, 1000f))
            .isEqualTo(VideoGestureMath.MIN_BRIGHTNESS)
    }

    @Test
    fun `volume fraction tracks the drag and clamps between 0 and 1`() {
        assertThat(VideoGestureMath.adjustVolumeFraction(0.5f, -250f, 1000f)).isWithin(1e-4f).of(0.75f)
        assertThat(VideoGestureMath.adjustVolumeFraction(0.9f, -500f, 1000f)).isEqualTo(1.0f)
        assertThat(VideoGestureMath.adjustVolumeFraction(0.1f, 500f, 1000f)).isEqualTo(0.0f)
    }

    @Test
    fun `volume fraction maps onto the stream's integer range`() {
        assertThat(VideoGestureMath.volumeIndex(0.0f, 15)).isEqualTo(0)
        assertThat(VideoGestureMath.volumeIndex(1.0f, 15)).isEqualTo(15)
        assertThat(VideoGestureMath.volumeIndex(0.5f, 15)).isEqualTo(7)
    }

    // ---- indicator formatting ----

    @Test
    fun `time preview is m ss under an hour and h mm ss at or over`() {
        assertThat(VideoGestureMath.formatTime(0L)).isEqualTo("0:00")
        assertThat(VideoGestureMath.formatTime(65_000L)).isEqualTo("1:05")
        assertThat(VideoGestureMath.formatTime(3_661_000L)).isEqualTo("1:01:01")
        assertThat(VideoGestureMath.formatTime(-5_000L)).isEqualTo("0:00")
    }

    @Test
    fun `seek label shows a signed second count`() {
        assertThat(VideoGestureMath.seekLabel(10_000L)).isEqualTo("+10s")
        assertThat(VideoGestureMath.seekLabel(-20_000L)).isEqualTo("−20s")
    }
}
