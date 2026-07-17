package com.appblish.calculatorvault.ui.theme

import androidx.compose.ui.graphics.Color

/** On-accent inks: dark near-black for bright swatches, white for saturated ones. */
private val InkDark = Color(0xFF06120A)
private val InkLight = Color(0xFFFFFFFF)

/**
 * The curated accent palette shown as a tappable swatch grid in Settings → Appearance
 * (APP-525, spec §1.2). The accent is a **single design token**: picking a swatch swaps
 * [swatch]/[pressed] everywhere the app reads `VaultTheme.colors.accent`, so the whole app
 * recolors instantly. Persisted by name through the settings store.
 *
 * Shades are a tasteful, cohesive set (Tailwind 500 / 600 family) so no swatch looks cheap.
 * The Principal Product Designer owns the final palette and exact shades (spec §1.2 routes
 * palette selection to design); these are the engineering-provisional values so the token
 * system, persistence, and instant-recolor plumbing are complete and shippable. Swapping a
 * hex here retunes a swatch app-wide with no other change.
 *
 * [onInk] is the foreground drawn on top of the swatch (the `=` key label, FAB icon, selected
 * check-mark). It is picked per-swatch for legibility — dark ink on the bright swatches
 * (Green, Amber, Cyan), white on the saturated ones — rather than a single fixed threshold,
 * which mis-classifies mid-luminance hues.
 */
enum class AccentColor(
    val displayName: String,
    val swatch: Color,
    val pressed: Color,
    val onInk: Color,
) {
    /** Default per spec §1.2 — the app's primary is now blue, not green. */
    BLUE("Blue", Color(0xFF3B82F6), Color(0xFF2563EB), InkLight),
    TEAL("Teal", Color(0xFF14B8A6), Color(0xFF0D9488), InkLight),
    GREEN("Green", Color(0xFF22C55E), Color(0xFF16A34A), InkDark),
    INDIGO("Indigo", Color(0xFF6366F1), Color(0xFF4F46E5), InkLight),
    PURPLE("Purple", Color(0xFF8B5CF6), Color(0xFF7C3AED), InkLight),
    PINK("Pink", Color(0xFFEC4899), Color(0xFFDB2777), InkLight),
    ROSE("Rose", Color(0xFFF43F5E), Color(0xFFE11D48), InkLight),
    ORANGE("Orange", Color(0xFFF97316), Color(0xFFEA580C), InkLight),
    AMBER("Amber", Color(0xFFF59E0B), Color(0xFFD97706), InkDark),
    CYAN("Cyan", Color(0xFF06B6D4), Color(0xFF0891B2), InkDark),
    ;

    companion object {
        /** First-run default per spec §1.2 — Blue. */
        val DEFAULT = BLUE

        fun fromNameOrNull(name: String?): AccentColor? = entries.firstOrNull { it.name == name }
    }
}
