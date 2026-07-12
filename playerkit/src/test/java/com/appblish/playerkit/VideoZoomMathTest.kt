package com.appblish.playerkit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-402 — pinch-zoom / pan clamp math (extracted from the origin app, spec §5: "pinch-to-zoom ...
 * pannable when zoomed"), pinned on the JVM so "pinch-to-zoom zooms and pans when zoomed" is proven
 * by the clamp rules rather than only by device feel.
 */
class VideoZoomMathTest {
    @Test
    fun `scale clamps into the min-max band`() {
        assertThat(VideoZoomMath.clampScale(0.2f)).isEqualTo(VideoZoomMath.MIN_SCALE)
        assertThat(VideoZoomMath.clampScale(9f)).isEqualTo(VideoZoomMath.MAX_SCALE)
        assertThat(VideoZoomMath.clampScale(2.5f)).isEqualTo(2.5f)
    }

    @Test
    fun `isZoomed is false at 1x and true once past it`() {
        assertThat(VideoZoomMath.isZoomed(1.0f)).isFalse()
        assertThat(VideoZoomMath.isZoomed(1.5f)).isTrue()
    }

    @Test
    fun `no pan room at 1x, grows with scale`() {
        assertThat(VideoZoomMath.maxPanX(1000f, 1.0f)).isEqualTo(0f)
        assertThat(VideoZoomMath.maxPanX(1000f, 2.0f)).isEqualTo(500f)
        assertThat(VideoZoomMath.maxPanY(800f, 2.0f)).isEqualTo(400f)
    }

    @Test
    fun `pan clamps to the reachable band per axis`() {
        val (x, y) = VideoZoomMath.clampPan(9999f, -9999f, 500f, 400f)
        assertThat(x).isEqualTo(500f)
        assertThat(y).isEqualTo(-400f)
    }

    @Test
    fun `applyPinch zooms and clamps the pan within bounds`() {
        val (scale, x, y) =
            VideoZoomMath.applyPinch(
                scale = 1.0f,
                panX = 0f,
                panY = 0f,
                pinchZoom = 2.0f,
                panDeltaX = 10_000f,
                panDeltaY = 0f,
                containerW = 1000f,
                containerH = 800f,
            )
        assertThat(scale).isEqualTo(2.0f)
        assertThat(x).isEqualTo(500f)
        assertThat(y).isEqualTo(0f)
    }

    @Test
    fun `applyPinch recentres when zoomed back to 1x`() {
        val (scale, x, y) =
            VideoZoomMath.applyPinch(
                scale = 2.0f,
                panX = 300f,
                panY = 120f,
                pinchZoom = 0.1f,
                panDeltaX = 50f,
                panDeltaY = 50f,
                containerW = 1000f,
                containerH = 800f,
            )
        assertThat(scale).isEqualTo(VideoZoomMath.MIN_SCALE)
        assertThat(x).isEqualTo(0f)
        assertThat(y).isEqualTo(0f)
    }
}
