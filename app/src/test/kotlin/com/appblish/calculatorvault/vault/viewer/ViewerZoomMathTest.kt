package com.appblish.calculatorvault.vault.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-299 P2-4 — the double-tap centring + pan-bounds contract for the viewer, unit-tested
 * without a device. Two concrete defects on `main @ 2e006c6` are pinned here:
 *  1. double-tap centring used `(center − tap)·scale` (over-shoots) instead of
 *     `(center − tap)·(scale − 1)`, so the tapped point was not held stationary;
 *  2. pan was clamped to the *container* rather than the *fitted* image, so a letterboxed
 *     photo could be dragged past its own edge into the black margin.
 */
class ViewerZoomMathTest {
    @Test
    fun `focusOffset keeps the tapped point stationary under the zoom`() {
        // Container 1000×1000; tap the top-left corner; step to 2×.
        val tap = Offset(0f, 0f)
        val offset = ViewerZoomMath.focusOffset(tap, 1000f, 1000f, 2f)
        // Correct centring: (center − tap)·(scale − 1) = (500,500)·1 = (500,500).
        assertThat(offset).isEqualTo(Offset(500f, 500f))

        // Prove the tapped point maps back to itself: screen = C + scale·(p − C) + t.
        val center = Offset(500f, 500f)
        val mapped = center + (tap - center) * 2f + offset
        assertThat(mapped).isEqualTo(tap)
    }

    @Test
    fun `focusOffset is not the old over-shooting formula`() {
        // The buggy `·scale` value would have been (500,500)·2 = (1000,1000).
        val offset = ViewerZoomMath.focusOffset(Offset(0f, 0f), 1000f, 1000f, 2f)
        assertThat(offset).isNotEqualTo(Offset(1000f, 1000f))
    }

    @Test
    fun `a centre double-tap needs no pan`() {
        val offset = ViewerZoomMath.focusOffset(Offset(500f, 500f), 1000f, 1000f, 2f)
        assertThat(offset).isEqualTo(Offset.Zero)
    }

    @Test
    fun `fitted content of a wide image is letterboxed and its pan bounds bar the black margin`() {
        // 2000×1000 image in a 1000×1000 box → fitted 1000×500 (top/bottom letterbox).
        val fitted = ViewerZoomMath.fittedContentSize(1000f, 1000f, 2000f, 1000f, rotationDegrees = 0)
        assertThat(fitted).isEqualTo(Size(1000f, 500f))

        // At 2×: horizontally the content overflows (pan 500), vertically it exactly fills
        // the box (pan 0) — so the user can never drag into the letterbox.
        val max = ViewerZoomMath.maxPan(1000f, 1000f, fitted, scale = 2f)
        assertThat(max).isEqualTo(Offset(500f, 0f))
    }

    @Test
    fun `container-based bounds would have wrongly allowed vertical pan`() {
        // The old clamp used container*(scale-1)/2 = 1000*1/2 = 500 on BOTH axes; the
        // content-aware bound is 0 vertically. This is the "past edges" regression.
        val fitted = ViewerZoomMath.fittedContentSize(1000f, 1000f, 2000f, 1000f, rotationDegrees = 0)
        val contentMaxY = ViewerZoomMath.maxPan(1000f, 1000f, fitted, 2f).y
        val containerMaxY = 1000f * (2f - 1f) / 2f
        assertThat(contentMaxY).isEqualTo(0f)
        assertThat(containerMaxY).isEqualTo(500f)
    }

    @Test
    fun `rotation quadrant swaps the fitted aspect`() {
        // Same wide image rotated 90° must fit as if it were tall (1000×2000).
        val fitted = ViewerZoomMath.fittedContentSize(1000f, 1000f, 2000f, 1000f, rotationDegrees = 90)
        assertThat(fitted).isEqualTo(Size(500f, 1000f))
        val max = ViewerZoomMath.maxPan(1000f, 1000f, fitted, 2f)
        assertThat(max).isEqualTo(Offset(0f, 500f))
    }

    @Test
    fun `clamp keeps every corner reachable and never past the bound`() {
        val max = Offset(500f, 300f)
        assertThat(ViewerZoomMath.clamp(Offset(900f, 900f), max)).isEqualTo(Offset(500f, 300f))
        assertThat(ViewerZoomMath.clamp(Offset(-900f, -900f), max)).isEqualTo(Offset(-500f, -300f))
        assertThat(ViewerZoomMath.clamp(Offset(120f, -50f), max)).isEqualTo(Offset(120f, -50f))
    }

    @Test
    fun `no overflow at fit scale means no pan`() {
        val fitted = ViewerZoomMath.fittedContentSize(1000f, 1000f, 1000f, 1000f, rotationDegrees = 0)
        assertThat(ViewerZoomMath.maxPan(1000f, 1000f, fitted, 1f)).isEqualTo(Offset.Zero)
    }
}
