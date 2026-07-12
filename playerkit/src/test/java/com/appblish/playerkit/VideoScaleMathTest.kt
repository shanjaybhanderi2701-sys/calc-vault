package com.appblish.playerkit

import com.appblish.playerkit.VideoScaleMath.AspectMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-402 — aspect / display-mode / rotation transitions for the shared player controls (extracted
 * from the origin app, spec §5, §5c), pinned on the JVM so "Rotation cycles 0/90/180/270; Display mode
 * cycles Fit/Fill" is proven by the rules.
 */
class VideoScaleMathTest {
    // ---- ⋯-menu aspect cycle (Fit / Fill / Zoom / Stretch) ----

    @Test
    fun `aspect cycles Fit to Fill to Zoom to Stretch and wraps`() {
        assertThat(VideoScaleMath.nextAspect(AspectMode.FIT)).isEqualTo(AspectMode.FILL)
        assertThat(VideoScaleMath.nextAspect(AspectMode.FILL)).isEqualTo(AspectMode.ZOOM)
        assertThat(VideoScaleMath.nextAspect(AspectMode.ZOOM)).isEqualTo(AspectMode.STRETCH)
        assertThat(VideoScaleMath.nextAspect(AspectMode.STRETCH)).isEqualTo(AspectMode.FIT)
    }

    // ---- Display-mode button: Fit <-> Fill only ----

    @Test
    fun `display mode toggles between Fit and Fill`() {
        assertThat(VideoScaleMath.nextDisplayMode(AspectMode.FIT)).isEqualTo(AspectMode.FILL)
        assertThat(VideoScaleMath.nextDisplayMode(AspectMode.FILL)).isEqualTo(AspectMode.FIT)
    }

    @Test
    fun `display mode from Zoom or Stretch lands on Fit`() {
        assertThat(VideoScaleMath.nextDisplayMode(AspectMode.ZOOM)).isEqualTo(AspectMode.FIT)
        assertThat(VideoScaleMath.nextDisplayMode(AspectMode.STRETCH)).isEqualTo(AspectMode.FIT)
    }

    // ---- Rotation button: 0 -> 90 -> 180 -> 270 -> 0 ----

    @Test
    fun `rotation cycles through the four quarter turns and wraps`() {
        assertThat(VideoScaleMath.nextRotation(0)).isEqualTo(90)
        assertThat(VideoScaleMath.nextRotation(90)).isEqualTo(180)
        assertThat(VideoScaleMath.nextRotation(180)).isEqualTo(270)
        assertThat(VideoScaleMath.nextRotation(270)).isEqualTo(0)
    }

    @Test
    fun `rotation normalizes negative and over-360 inputs before advancing`() {
        assertThat(VideoScaleMath.nextRotation(-90)).isEqualTo(0)
        assertThat(VideoScaleMath.nextRotation(360)).isEqualTo(90)
        assertThat(VideoScaleMath.nextRotation(450)).isEqualTo(180)
    }

    @Test
    fun `normalizeRotation snaps off-grid degrees to the nearest quarter`() {
        assertThat(VideoScaleMath.normalizeRotation(80)).isEqualTo(90)
        assertThat(VideoScaleMath.normalizeRotation(200)).isEqualTo(180)
        assertThat(VideoScaleMath.normalizeRotation(0)).isEqualTo(0)
    }
}
