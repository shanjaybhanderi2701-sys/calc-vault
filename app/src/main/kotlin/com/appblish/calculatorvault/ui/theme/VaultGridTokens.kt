package com.appblish.calculatorvault.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Wave-3 layer-2 grid tokens (W3-D §3), composed from the design system's layer-1 values
 * only — no new brand color, no ad-hoc sizes outside this file. Follows the shipped
 * pattern of `VaultViewerTokens` (the viewer chrome constants in PagerViewerScreen):
 * token *names* live here; surfaces reference them, never literals.
 */
object VaultGridTokens {
    // album.pinBadge — filled pin glyph 14dp on a 24dp circle. The badge sits at the
    // top-END of the album cover (top-start belongs to select.check; the two never
    // collide on a selected pinned tile). Colors are the live accent token, not a
    // literal hue — glyph = `colors.accent` on `colors.accentContainer` (spec A3).
    val PinBadgeSize = 24.dp
    val PinBadgeGlyphSize = 14.dp

    // cover.selectRing — accent 2dp ring inside the chosen tile's bounds (spec A3/§6);
    // the ring color is `colors.accent` at the call site.
    val CoverRingWidth = 2.dp

    // control.segmented — the sort sheet's Ascending/Descending pill (§7). Selected
    // container = `colors.accentContainer`, label = `colors.accent` (spec B2).
    val SegmentedHeight = 40.dp

    // sort.sheet radio rows / viewer.moreMenu items — 48dp minimum touch height.
    val MenuRowHeight = 48.dp

    /**
     * Filled push-pin glyph (Material `push_pin`) — material-icons-core ships only the
     * base icon set, so the badge glyph is inlined here rather than pulling the whole
     * extended-icons artifact for one path.
     */
    val PushPin: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "Filled.PushPin",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(
                pathData =
                    addPathNodes(
                        "M16,9V4h1c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3" +
                            "s0.45,1 1,1h1v5c0,1.66 -1.34,3 -3,3v2h5.97v7l1,1 1,-1v-7H19v-2" +
                            "c-1.66,0 -3,-1.34 -3,-3z",
                    ),
                fill = SolidColor(Color.Black),
            ).build()
    }
}
