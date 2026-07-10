package com.appblish.calculatorvault.vault.viewer

/**
 * CalcVault Phase B · Wave 3 · APP-350 — the playback-speed option set and label formatting
 * for the §5c speed dialog. Pure so the option list and "1.0x"-style labels are unit-testable;
 * the Compose layer just calls `ExoPlayer.setPlaybackSpeed(speed)` (pitch-corrected by Media3's
 * default `SonicAudioProcessor`, spec §5b).
 */
object PlaybackSpeeds {
    // APP-381 · the owner design-reference doc lists 0.5 / 1.0 / 1.5 / 2.0 ("0.5× increments,
    // 0.5×–2.0×"). The doc's prose says "five speed settings" but only names these four — the
    // intended 5th step (e.g. 0.75× or 2.5×) is flagged to the owner for confirmation, so we ship
    // the four unambiguous values. The label formatter keeps its two-decimal branch so a future
    // 0.75× the owner may confirm renders correctly without another change.

    /** The exact dialog choices per the owner design reference: 0.5 / 1.0 / 1.5 / 2×. */
    val OPTIONS: List<Float> = listOf(0.5f, 1.0f, 1.5f, 2.0f)

    /** Normal speed; where playback starts and what the speed button resets to. */
    const val DEFAULT: Float = 1.0f

    /** One-decimal "×" label for the button/dialog, e.g. 1f → "1.0x", 0.75f → "0.75x". */
    fun label(speed: Float): String {
        // Keep 0.75 readable (two decimals) but render the round steps as one decimal.
        val rounded = Math.round(speed * 100f) / 100f
        return if (rounded * 10f % 1f == 0f) {
            "%.1fx".format(rounded)
        } else {
            "%.2fx".format(rounded)
        }
    }

    /** True when [speed] is (within rounding of) normal 1× — lets the UI de-emphasise the badge. */
    fun isDefault(speed: Float): Boolean = kotlin.math.abs(speed - DEFAULT) < 0.001f

    /** Snaps an arbitrary speed (e.g. a restored pref) to the closest supported [OPTIONS] step. */
    fun nearest(speed: Float): Float = OPTIONS.minByOrNull { kotlin.math.abs(it - speed) } ?: DEFAULT
}
