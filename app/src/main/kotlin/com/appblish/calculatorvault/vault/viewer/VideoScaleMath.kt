package com.appblish.calculatorvault.vault.viewer

/**
 * CalcVault Phase B · Wave 3 · APP-350 — pure decision core for the video player's
 * **aspect / display-mode / rotation** controls (spec §5 & §5c). Like [VideoGestureMath],
 * every rule that decides *which* mode a control lands on lives here as a side-effect-free
 * function so it is unit-testable on the JVM; the Compose layer only maps the resulting
 * [AspectMode] onto `PlayerView`'s `resizeMode` and applies the rotation to the surface.
 *
 * Two distinct controls touch scale (spec is explicit that they differ):
 *  - The **⋯-menu aspect chooser** ([nextAspect]) cycles the full set Fit → Fill → Zoom →
 *    Stretch (§5, "cycle or choose Fit / Fill / Zoom / Stretch").
 *  - The **Display-mode button** ([nextDisplayMode]) is the reference-doc quick control and
 *    only toggles Fit ⇄ Fill (§5c, "cycles between Fit to Screen and Fill Screen").
 *
 * Pinch-to-zoom (spec §5) is *additional* and lives in [VideoZoomMath]; it composes on top of
 * whichever [AspectMode] is active.
 */
object VideoScaleMath {
    /**
     * The four base aspect modes (spec §5). Each maps to exactly one `PlayerView` resize mode
     * in the Compose layer:
     *  - [FIT] → whole video, original ratio, letterboxed (`RESIZE_MODE_FIT`).
     *  - [FILL] → fills the screen keeping ratio, may crop (`RESIZE_MODE_ZOOM`).
     *  - [ZOOM] → same fill-crop family, exposed as its own menu entry per the reference doc.
     *  - [STRETCH] → stretches to fill ignoring ratio (`RESIZE_MODE_FILL`).
     */
    enum class AspectMode(val label: String) {
        FIT("Fit"),
        FILL("Fill"),
        ZOOM("Zoom"),
        STRETCH("Stretch"),
    }

    /** Full ⋯-menu aspect cycle (spec §5), wrapping Fit → Fill → Zoom → Stretch → Fit. */
    fun nextAspect(current: AspectMode): AspectMode {
        val values = AspectMode.entries
        return values[(current.ordinal + 1) % values.size]
    }

    /** The two modes the §5c Display-mode quick button toggles between. */
    val DISPLAY_MODES: List<AspectMode> = listOf(AspectMode.FIT, AspectMode.FILL)

    /**
     * The §5c Display-mode button: toggles **Fit ⇄ Fill** only. From any other mode (Zoom /
     * Stretch set via the ⋯ menu) it resolves to Fit, so the quick button always lands on a
     * well-defined member of [DISPLAY_MODES].
     */
    fun nextDisplayMode(current: AspectMode): AspectMode =
        if (current == AspectMode.FIT) AspectMode.FILL else AspectMode.FIT

    /** The rotation stops the §5c Rotation button cycles through, in order. */
    val ROTATIONS: List<Int> = listOf(0, 90, 180, 270)

    /** Normalizes any degree value into the canonical {0, 90, 180, 270} bucket. */
    fun normalizeRotation(degrees: Int): Int {
        val q = ((degrees % 360) + 360) % 360
        // Snap to the nearest quarter turn so stray values still cycle cleanly.
        return ROTATIONS.minByOrNull { kotlin.math.abs(it - q).coerceAtMost(360 - kotlin.math.abs(it - q)) } ?: 0
    }

    /**
     * The §5c Rotation button: advances 0° → 90° → 180° → 270° → 0° per tap. Any off-grid
     * input is normalized first so the cycle never gets stuck.
     */
    fun nextRotation(currentDegrees: Int): Int {
        val current = normalizeRotation(currentDegrees)
        val idx = ROTATIONS.indexOf(current).coerceAtLeast(0)
        return ROTATIONS[(idx + 1) % ROTATIONS.size]
    }
}
