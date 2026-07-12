package com.appblish.playerkit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-side pinch/pan state for the shared video surface (APP-408), a thin holder over the pure
 * [VideoZoomMath] core (APP-402). It carries the mutable scale/offset that `graphicsLayer` applies;
 * every clamp decision defers to [VideoZoomMath] so the tested JVM math is the single source of
 * truth and the Compose layer stays a dispatcher.
 *
 * Gesture arbitration mirrors the photo viewer for muscle-memory parity: at 1× a single-finger drag
 * is LEFT UNCONSUMED so the host pager can swipe between media; a pinch (≥2 pointers) or any pan
 * while already zoomed is CONSUMED here so the pager freezes. Releasing a pinch back to 1× re-centres
 * ([VideoZoomMath.applyPinch] snaps the offset to (0,0)), which re-arms the pager.
 */
@Stable
class VideoZoomState {
    var scale by mutableFloatStateOf(VideoZoomMath.MIN_SCALE)
        private set
    var panX by mutableFloatStateOf(0f)
        private set
    var panY by mutableFloatStateOf(0f)
        private set

    /** Viewport size in px, fed from `onSizeChanged`; drives the pan clamps. */
    var containerWidth by mutableFloatStateOf(0f)
    var containerHeight by mutableFloatStateOf(0f)

    val isZoomed: Boolean get() = VideoZoomMath.isZoomed(scale)

    /** Consume the gesture iff it is multi-touch or the surface is already zoomed (see class doc). */
    fun shouldConsume(pointerCount: Int): Boolean = pointerCount > 1 || isZoomed

    /** Fold one pinch/pan increment through [VideoZoomMath], updating scale + clamped offset. */
    fun applyPinch(pinchZoom: Float, panDeltaX: Float, panDeltaY: Float) {
        val (newScale, newX, newY) = VideoZoomMath.applyPinch(
            scale = scale,
            panX = panX,
            panY = panY,
            pinchZoom = pinchZoom,
            panDeltaX = panDeltaX,
            panDeltaY = panDeltaY,
            containerW = containerWidth,
            containerH = containerHeight,
        )
        scale = newScale
        panX = newX
        panY = newY
    }

    /** Reset to 1× centred — called when the page changes so a new clip never inherits a zoom. */
    fun reset() {
        scale = VideoZoomMath.MIN_SCALE
        panX = 0f
        panY = 0f
    }
}
