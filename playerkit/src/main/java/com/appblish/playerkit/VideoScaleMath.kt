package com.appblish.playerkit

/**
 * Pure decision core for the shared video player's **aspect / display-mode / rotation** controls
 * (APP-402, extracted verbatim from the origin app, spec §5 & §5c). Like [VideoGestureMath], every rule
 * that decides *which* mode a control lands on lives here as a side-effect-free function so it is
 * unit-testable on the JVM; the Compose layer only maps the resulting [AspectMode] onto the
 * `PlayerView`/surface `resizeMode` and applies the rotation.
 *
 * Two distinct controls touch scale:
 *  - The **⋯-menu aspect chooser** ([nextAspect]) cycles the full set Fit → Fill → Zoom → Stretch.
 *  - The **Display-mode button** ([nextDisplayMode]) is the quick control and only toggles Fit ⇄ Fill.
 *
 * Pinch-to-zoom is *additional* and lives in [VideoZoomMath]; it composes on top of whichever
 * [AspectMode] is active.
 */
object VideoScaleMath {
    /**
     * The four base aspect modes. Each maps to exactly one resize mode in the Compose layer:
     *  - [FIT] → whole video, original ratio, letterboxed.
     *  - [FILL] → fills the screen keeping ratio, may crop.
     *  - [ZOOM] → same fill-crop family, exposed as its own menu entry.
     *  - [STRETCH] → stretches to fill ignoring ratio.
     */
    enum class AspectMode(
        val label: String,
    ) {
        FIT("Fit"),
        FILL("Fill"),
        ZOOM("Zoom"),
        STRETCH("Stretch"),
    }

    /** Full ⋯-menu aspect cycle, wrapping Fit → Fill → Zoom → Stretch → Fit. */
    fun nextAspect(current: AspectMode): AspectMode {
        val values = AspectMode.entries
        return values[(current.ordinal + 1) % values.size]
    }

    /** The two modes the Display-mode quick button toggles between. */
    val DISPLAY_MODES: List<AspectMode> = listOf(AspectMode.FIT, AspectMode.FILL)

    /**
     * The Display-mode button: toggles **Fit ⇄ Fill** only. From any other mode (Zoom / Stretch set
     * via the ⋯ menu) it resolves to Fit, so the quick button always lands on a well-defined member
     * of [DISPLAY_MODES].
     */
    fun nextDisplayMode(current: AspectMode): AspectMode =
        if (current == AspectMode.FIT) AspectMode.FILL else AspectMode.FIT

    /** The rotation stops the Rotation button cycles through, in order. */
    val ROTATIONS: List<Int> = listOf(0, 90, 180, 270)

    /** Normalizes any degree value into the canonical {0, 90, 180, 270} bucket. */
    fun normalizeRotation(degrees: Int): Int {
        val q = ((degrees % 360) + 360) % 360
        // Snap to the nearest quarter turn so stray values still cycle cleanly.
        return ROTATIONS.minByOrNull {
            kotlin.math.abs(it - q).coerceAtMost(360 - kotlin.math.abs(it - q))
        } ?: 0
    }

    /**
     * The Rotation button: advances 0° → 90° → 180° → 270° → 0° per tap. Any off-grid input is
     * normalized first so the cycle never gets stuck.
     */
    fun nextRotation(currentDegrees: Int): Int {
        val current = normalizeRotation(currentDegrees)
        val idx = ROTATIONS.indexOf(current).coerceAtLeast(0)
        return ROTATIONS[(idx + 1) % ROTATIONS.size]
    }
}
