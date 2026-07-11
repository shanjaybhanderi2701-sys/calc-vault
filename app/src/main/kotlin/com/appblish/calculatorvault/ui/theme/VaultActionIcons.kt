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

    // ---- Wave 3 (APP-350) in-vault video-player controls (spec §5c) ----
    // material-icons-core has none of these transport/display glyphs; inlined here per the
    // same rule (do NOT add material-icons-extended for ~9 icons). See the W3 design doc
    // `w3-controls-layout-signoff` §7 (icon feasibility gate).

    /** Material `pause` — the play/pause toggle's paused-state glyph (core lacks Pause). */
    val Pause: ImageVector by lazy {
        icon(
            name = "Filled.Pause",
            pathData = "M6,19h4V5H6v14zM14,5v14h4V5h-4z",
        )
    }

    /**
     * Material `lock` — the closed-padlock glyph for the §5c **Lock controls** button (APP-384
     * MX-Player redesign). The bottom-bar lock button must read as "lock", not the old
     * `lock_open` glyph it borrowed from [Unhide].
     */
    val Lock: ImageVector by lazy {
        icon(
            name = "Filled.Lock",
            pathData =
                "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 " +
                    "0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 " +
                    "-2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 " +
                    "1.71,0 3.1,1.39 3.1,3.1v2z",
        )
    }

    /** Material `speed` — the §5c Playback-speed control glyph (APP-384 bottom-bar speed button). */
    val Speed: ImageVector by lazy {
        icon(
            name = "Filled.Speed",
            pathData =
                "M20.38,8.57l-1.23,1.85a8,8 0 0 1 -0.22,7.58H5.07A8,8 0 0 1 15.58,6.85l1.85,-1.23A10,10 " +
                    "0 0 0 3.35,19a2,2 0 0 0 1.72,1h13.85a2,2 0 0 0 1.74,-1 10,10 0 0 0 -0.27,-10.44z" +
                    "m-9.79,6.84a2,2 0 0 0 2.83,0l5.66,-8.49 -8.49,5.66a2,2 0 0 0 0,2.83z",
        )
    }

    /** Material `brightness_high` — the §3 brightness gesture-overlay glyph (APP-384). */
    val Brightness: ImageVector by lazy {
        icon(
            name = "Filled.BrightnessHigh",
            pathData =
                "M12,9c1.65,0 3,1.35 3,3s-1.35,3 -3,3 -3,-1.35 -3,-3 1.35,-3 3,-3M12,7c-2.76,0 -5,2.24 " +
                    "-5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5L12,7zM2,13l2,0c0.55,0 1,-0.45 1,-1s-0.45," +
                    "-1 -1,-1l-2,0c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM20,13l2,0c0.55,0 1,-0.45 1,-1s-0.45," +
                    "-1 -1,-1l-2,0c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM11,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1" +
                    "L13,2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1zM11,20v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2" +
                    "c0,-0.55 -0.45,-1 -1,-1 -0.55,0 -1,0.45 -1,1zM5.99,4.58c-0.39,-0.39 -1.03,-0.39 " +
                    "-1.41,0 -0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0s0.39,-1.03 " +
                    "0,-1.41L5.99,4.58zM18.36,16.95c-0.39,-0.39 -1.03,-0.39 -1.41,0 -0.39,0.39 -0.39,1.03 " +
                    "0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0 0.39,-0.39 0.39,-1.03 0,-1.41l-1.06,-1.06z" +
                    "M19.42,5.99c0.39,-0.39 0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06" +
                    "c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06zM7.05,18.36c0.39,-0.39 " +
                    "0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 " +
                    "0,1.41s1.03,0.39 1.41,0l1.06,-1.06z",
        )
    }

    /** Material `lock_open` — the unlock-pill glyph in the locked overlay (§5c / §6). */
    val LockOpen: ImageVector by lazy {
        icon(
            name = "Filled.LockOpen",
            pathData =
                "M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18,8h-1V6" +
                    "c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6h1.9c0,-1.71 1.39,-3.1 3.1,-3.1s3.1,1.39 " +
                    "3.1,3.1v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10" +
                    "c0,-1.1 -0.9,-2 -2,-2zM18,20H6V10h12v10z",
        )
    }

    /** Material `fullscreen` — the §5c Full-screen control glyph. */
    val Fullscreen: ImageVector by lazy {
        icon(
            name = "Filled.Fullscreen",
            pathData =
                "M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z",
        )
    }

    /**
     * Material `picture_in_picture_alt` — the §5c Wave-4 **Mini Player** control glyph (APP-351):
     * tap in the full player to minimize into the in-app floating window. Same glyph the mini
     * window's Expand affordance inverts back to [Fullscreen].
     */
    val PictureInPicture: ImageVector by lazy {
        icon(
            name = "Filled.PictureInPictureAlt",
            pathData =
                "M19,11h-8v6h8V11zM17,15h-4v-2h4V15zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18" +
                    "c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3zM21,19.01H3V4.98h18V19.01z",
        )
    }

    /** Material `volume_up` — the §5c Volume control, un-muted state. */
    val VolumeOn: ImageVector by lazy {
        icon(
            name = "Filled.VolumeUp",
            pathData =
                "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 " +
                    "2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71" +
                    "v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }

    /** Material `volume_off` — the §5c Volume control, muted state (mute toggle). */
    val VolumeOff: ImageVector by lazy {
        icon(
            name = "Filled.VolumeOff",
            pathData =
                "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 " +
                    "0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 " +
                    "21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 " +
                    "7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 " +
                    "2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4l-2.09,2.09L12,8.18V4z",
        )
    }

    /** Material `screen_rotation` — the §5c Rotation (90° cycle) control glyph. */
    val ScreenRotation: ImageVector by lazy {
        icon(
            name = "Filled.ScreenRotation",
            pathData =
                "M16.48,2.52c3.27,1.55 5.61,4.72 5.97,8.48h1.5C23.44,4.84 18.29,0 12,0l-0.66,0.03 " +
                    "3.81,3.81 1.33,-1.32zM10.23,1.75c-0.59,-0.59 -1.54,-0.59 -2.12,0L1.75,8.11" +
                    "c-0.59,0.59 -0.59,1.54 0,2.12l12.02,12.02c0.59,0.59 1.54,0.59 2.12,0l6.36,-6.36" +
                    "c0.59,-0.59 0.59,-1.54 0,-2.12L10.23,1.75zM14.83,21.19L2.81,9.17l6.36,-6.36 " +
                    "12.02,12.02 -6.36,6.36zM7.52,21.48C4.25,19.94 1.91,16.76 1.55,13h-1.5" +
                    "C0.56,19.16 5.71,24 12,24l0.66,-0.03 -3.81,-3.81 -1.33,1.32z",
        )
    }

    /** Material `aspect_ratio` — the §5c Display-mode (Fit/Fill) control glyph. */
    val AspectRatio: ImageVector by lazy {
        icon(
            name = "Filled.AspectRatio",
            pathData =
                "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 " +
                    "0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z",
        )
    }

    /** Material `skip_previous` — the §5d transport Prev control glyph. */
    val SkipPrevious: ImageVector by lazy {
        icon(
            name = "Filled.SkipPrevious",
            pathData = "M6,6h2v12H6zM9.5,12l8.5,6V6z",
        )
    }

    /** Material `skip_next` — the §5d transport Next control glyph. */
    val SkipNext: ImageVector by lazy {
        icon(
            name = "Filled.SkipNext",
            pathData = "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z",
        )
    }

    /** Material `subtitles` — the §4 subtitles entry glyph (⋯ menu). */
    val Subtitles: ImageVector by lazy {
        icon(
            name = "Filled.Subtitles",
            pathData =
                "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6" +
                    "c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2z" +
                    "M20,14H10v-2h10v2z",
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
