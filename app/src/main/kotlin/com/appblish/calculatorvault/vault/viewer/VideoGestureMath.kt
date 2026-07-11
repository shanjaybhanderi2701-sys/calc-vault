package com.appblish.calculatorvault.vault.viewer

import kotlin.math.abs

/**
 * CalcVault Phase B · Wave 2 · APP-349 — pure gesture-resolution core for the in-vault
 * video player (spec §3). Every rule that decides *which* gesture a touch is and *how far*
 * it moves the player lives here as side-effect-free functions so it is unit-testable on the
 * JVM and the Compose overlay ([VideoPlayerSurface]) stays a thin dispatcher.
 *
 * **Gesture-zone map & priority (spec §3, design gate).** The player surface is split into a
 * LEFT and RIGHT half at the horizontal midpoint. A single finger resolves to exactly one
 * gesture, decided so nothing misfires:
 *
 *  - **Single tap** → toggle the controls chrome. (Emitted only after the double-tap
 *    window elapses, so a double-tap is never read as two single taps.)
 *  - **Double-tap** → seek: LEFT half −[SEEK_STEP_MS], RIGHT half +[SEEK_STEP_MS], and
 *    consecutive double-taps on the same side accumulate (−10s, −20s, …) like a scrubbing
 *    ratchet, each showing a brief indicator.
 *  - **Drag** → the *dominant axis* is latched once the finger passes touch-slop and cannot
 *    flip mid-gesture ([dominantAxis]), so a horizontal scrub never leaks into brightness:
 *      - **Vertical drag, LEFT half** → screen brightness (MX-Player convention, APP-384 #4).
 *      - **Vertical drag, RIGHT half** → media volume.
 *      - **Horizontal drag (either half)** → scrub with a time preview.
 *
 * Pinch (two-finger) is intentionally *not* handled here — Wave 3 adds pinch-zoom, and these
 * single-pointer rules must yield to a second pointer. The overlay only starts a drag gesture
 * for a single pointer, leaving multi-touch free for the Wave-3 transformable.
 */
object VideoGestureMath {
    /** One double-tap seek step (spec §3: "e.g. +10s"). */
    const val SEEK_STEP_MS: Long = 10_000L

    /**
     * Horizontal-scrub sensitivity: dragging a full surface-width left→right moves the
     * playhead by this much. Keeps scrub predictable regardless of clip length (the value is
     * clamped to the real duration in [scrubTargetMs]).
     */
    const val SCRUB_FULL_WIDTH_MS: Long = 90_000L

    /** Brightness never goes fully black (0f can read as "system default"); floor at 1%. */
    const val MIN_BRIGHTNESS: Float = 0.01f
    const val MAX_BRIGHTNESS: Float = 1.0f

    enum class Zone { LEFT, RIGHT }

    enum class Axis { HORIZONTAL, VERTICAL }

    /** Which half of the surface the touch started in. Exact midpoint counts as RIGHT. */
    fun zoneFor(
        x: Float,
        width: Float
    ): Zone = if (width <= 0f || x < width / 2f) Zone.LEFT else Zone.RIGHT

    /**
     * Latches the drag axis from the accumulated movement since the finger went down. A tie
     * (or no movement) resolves to [Axis.HORIZONTAL] so an ambiguous flick scrubs rather than
     * fights the vertical controls. Callers latch this ONCE (past touch-slop) and keep it for
     * the whole gesture so the axis can't flip.
     */
    fun dominantAxis(
        totalDx: Float,
        totalDy: Float
    ): Axis = if (abs(totalDx) >= abs(totalDy)) Axis.HORIZONTAL else Axis.VERTICAL

    /**
     * Signed seek delta for a double-tap in [zone] that is the [tapCount]-th consecutive
     * double-tap on that side (1-based). RIGHT adds, LEFT subtracts; magnitude ratchets by
     * [SEEK_STEP_MS] per repeat.
     */
    fun seekDeltaMs(
        zone: Zone,
        tapCount: Int,
    ): Long {
        val magnitude = SEEK_STEP_MS * tapCount.coerceAtLeast(1)
        return if (zone == Zone.LEFT) -magnitude else magnitude
    }

    /** Applies a signed seek delta to [currentMs], clamped to [0, totalMs]. */
    fun seekTo(
        currentMs: Long,
        deltaMs: Long,
        totalMs: Long,
    ): Long = (currentMs + deltaMs).coerceIn(0L, totalMs.coerceAtLeast(0L))

    /**
     * Absolute playhead target for a horizontal scrub: [startMs] plus a delta proportional to
     * the horizontal drag ([dragXpx] px over a surface [widthPx] px wide, scaled by
     * [SCRUB_FULL_WIDTH_MS]). Clamped to [0, totalMs]. A right-drag advances, left rewinds.
     */
    fun scrubTargetMs(
        startMs: Long,
        dragXpx: Float,
        widthPx: Float,
        totalMs: Long,
    ): Long {
        if (widthPx <= 0f) return startMs.coerceIn(0L, totalMs.coerceAtLeast(0L))
        val deltaMs = (dragXpx / widthPx) * SCRUB_FULL_WIDTH_MS
        return (startMs + deltaMs.toLong()).coerceIn(0L, totalMs.coerceAtLeast(0L))
    }

    /**
     * New brightness for a vertical drag of [dragYpx] px over a [heightPx]-tall surface.
     * Dragging UP (negative px, screen-space) brightens. Result is clamped to
     * [[MIN_BRIGHTNESS], [MAX_BRIGHTNESS]].
     */
    fun adjustBrightness(
        current: Float,
        dragYpx: Float,
        heightPx: Float,
    ): Float {
        if (heightPx <= 0f) return current.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        val next = current + (-dragYpx / heightPx)
        return next.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    }

    /**
     * New volume fraction (0f..1f) for a vertical drag of [dragYpx] px over a [heightPx]-tall
     * surface. Dragging UP raises volume. Callers map the fraction onto the audio stream's
     * integer range.
     */
    fun adjustVolumeFraction(
        current: Float,
        dragYpx: Float,
        heightPx: Float,
    ): Float {
        if (heightPx <= 0f) return current.coerceIn(0f, 1f)
        val next = current + (-dragYpx / heightPx)
        return next.coerceIn(0f, 1f)
    }

    /** Rounds a 0f..1f volume fraction onto an integer stream range [0, maxVolume]. */
    fun volumeIndex(
        fraction: Float,
        maxVolume: Int,
    ): Int = (fraction.coerceIn(0f, 1f) * maxVolume).toInt().coerceIn(0, maxVolume)

    /** "m:ss" under an hour, "h:mm:ss" at/over an hour. Negative inputs clamp to zero. */
    fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** Signed "+10s" / "−20s" label for a seek indicator. */
    fun seekLabel(deltaMs: Long): String {
        val seconds = abs(deltaMs) / 1000L
        val sign = if (deltaMs < 0L) "−" else "+"
        return "$sign${seconds}s"
    }
}
