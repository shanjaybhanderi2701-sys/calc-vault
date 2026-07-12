package com.appblish.playerkit

/**
 * Pure pinch-to-zoom / pan core for the shared video surface (APP-402, extracted verbatim from
 * the origin app, spec §5: "pinch-to-zoom to zoom into the video freely, pannable when zoomed"). Kept
 * side-effect-free so the clamp math is unit-testable on the JVM; the Compose layer feeds it raw
 * `Transformable`/`detectTransformGestures` deltas and applies the returned scale/offset to
 * `graphicsLayer`.
 *
 * **Coexistence with single-pointer gestures.** [VideoGestureMath] handles only single-pointer drags
 * (scrub / brightness / volume) and leaves multi-touch free. Pinch is a two-pointer gesture, so the
 * two never fight: the dispatcher routes ≥2 active pointers here and 1 pointer to [VideoGestureMath].
 * When zoomed back to [MIN_SCALE] the pan resets to centre so the video can never be stranded
 * off-screen.
 */
object VideoZoomMath {
    /** At 1× the video sits exactly as its [VideoScaleMath.AspectMode] lays it out — no zoom. */
    const val MIN_SCALE: Float = 1.0f

    /** Upper bound on pinch zoom; matches the photo viewer's ceiling for muscle-memory parity. */
    const val MAX_SCALE: Float = 5.0f

    /** Below this delta above 1× we treat the video as un-zoomed (pan disabled, offset centred). */
    private const val ZOOM_EPSILON: Float = 0.001f

    /** Clamps a candidate scale (e.g. `scale * pinchZoom`) into [[MIN_SCALE], [MAX_SCALE]]. */
    fun clampScale(candidate: Float): Float = candidate.coerceIn(MIN_SCALE, MAX_SCALE)

    /** True once the video is zoomed in past 1× — the point at which panning is allowed. */
    fun isZoomed(scale: Float): Boolean = scale > MIN_SCALE + ZOOM_EPSILON

    /**
     * Max |translation| from centre, per axis, that keeps a [containerW]×[containerH] surface scaled
     * by [scale] from being panned past its own edge into empty space. Zero on an axis when un-zoomed
     * (nothing overflows), so [clampPan] pins the video centred at 1×.
     */
    fun maxPanX(
        containerW: Float,
        scale: Float,
    ): Float = ((containerW * scale - containerW) / 2f).coerceAtLeast(0f)

    fun maxPanY(
        containerH: Float,
        scale: Float,
    ): Float = ((containerH * scale - containerH) / 2f).coerceAtLeast(0f)

    /** Clamps a candidate pan (x, y) to ±[maxX]/±[maxY] so every edge is reachable, none past. */
    fun clampPan(
        candidateX: Float,
        candidateY: Float,
        maxX: Float,
        maxY: Float,
    ): Pair<Float, Float> = candidateX.coerceIn(-maxX, maxX) to candidateY.coerceIn(-maxY, maxY)

    /**
     * Resolves a full pinch update in one call: applies [pinchZoom] to [scale], clamps it, then
     * clamps the panned offset to the new bounds. When the result is back at 1× the offset snaps to
     * (0, 0) so releasing a pinch always re-centres the video.
     *
     * @return Triple(newScale, newPanX, newPanY).
     */
    fun applyPinch(
        scale: Float,
        panX: Float,
        panY: Float,
        pinchZoom: Float,
        panDeltaX: Float,
        panDeltaY: Float,
        containerW: Float,
        containerH: Float,
    ): Triple<Float, Float, Float> {
        val newScale = clampScale(scale * pinchZoom)
        if (!isZoomed(newScale)) return Triple(MIN_SCALE, 0f, 0f)
        val maxX = maxPanX(containerW, newScale)
        val maxY = maxPanY(containerH, newScale)
        val (cx, cy) = clampPan(panX + panDeltaX, panY + panDeltaY, maxX, maxY)
        return Triple(newScale, cx, cy)
    }
}
