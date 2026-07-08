package com.appblish.calculatorvault.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Shared action glyphs for the vault surfaces (APP-293 device-test fix pass, items 1/11/12).
 * material-icons-core ships only the base set, so — following the [VaultGridTokens.PushPin]
 * pattern — the Material Symbols paths are inlined here rather than pulling the whole
 * extended-icons artifact for three glyphs.
 *
 *  - [Unhide]: `lock_open` — the gallery-exit verb's own icon, distinct from Share (the
 *    owner's P0-1: Unhide must never wear a share glyph).
 *  - [Sort]: `sort` — the contemporary three-bars sort glyph (replaces the ↑↓ chevron pair).
 *  - [MoveTo]: `drive_file_move` — folder-with-arrow for the relocate-to-album action
 *    (replaces the bare → chevron).
 */
object VaultActionIcons {
    /** Material `lock_open` — the Unhide (restore-out-of-vault) action glyph. */
    val Unhide: ImageVector by lazy {
        icon(
            name = "Filled.LockOpen",
            pathData =
                "M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18,8h-1V6" +
                    "c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6h1.9c0,-1.71 1.39,-3.1 3.1,-3.1s3.1,1.39 " +
                    "3.1,3.1v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10" +
                    "c0,-1.1 -0.9,-2 -2,-2zM18,20H6V10h12v10z",
        )
    }

    /** Material `sort` — the modern sort trigger glyph (fix-pass item 11). */
    val Sort: ImageVector by lazy {
        icon(
            name = "Filled.Sort",
            pathData = "M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z",
        )
    }

    /** Material `drive_file_move` — the move-to-album action glyph (fix-pass item 12). */
    val MoveTo: ImageVector by lazy {
        icon(
            name = "Filled.DriveFileMove",
            pathData =
                "M20,6h-8l-2,-2H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 " +
                    "2,-2V8C22,6.9 21.1,6 20,6zM12,18v-3H8v-4h4V8l5,5L12,18z",
        )
    }

    private fun icon(
        name: String,
        pathData: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}
