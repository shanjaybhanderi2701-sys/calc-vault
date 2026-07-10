package com.appblish.calculatorvault.vault.viewer

/**
 * CalcVault Phase B · Wave 3 · APP-371 (F4) — pure extension→MIME mapping for **external
 * subtitle side-loading** (spec §4). Kept side-effect-free and Media3-free so the whole
 * "which files are loadable subtitles / what sample MIME to hand a SingleSampleMediaSource"
 * decision is unit-testable on the JVM.
 *
 * Only the sidecar text-subtitle containers Media3 can extract are offered: SubRip (`.srt`),
 * SubStation Alpha (`.ass`/`.ssa`), and WebVTT (`.vtt`). Anything else (an embedded track, a
 * `.sub`/`.idx` bitmap pair, a random file) returns `null` — the picker/menu never offers a
 * format Media3 would fail to parse.
 *
 * The returned strings are the exact `androidx.media3.common.MimeTypes` sample-MIME constants
 * (inlined so this stays a pure module): a `SingleSampleMediaSource` subtitle configuration is
 * built with one of these as its MIME type.
 */
object SubtitleFormats {
    /** MimeTypes.APPLICATION_SUBRIP — SubRip (`.srt`). */
    const val MIME_SUBRIP = "application/x-subrip"

    /** MimeTypes.TEXT_SSA — SubStation Alpha / Advanced SSA (`.ssa`/`.ass`). */
    const val MIME_SSA = "text/x-ssa"

    /** MimeTypes.TEXT_VTT — WebVTT (`.vtt`). */
    const val MIME_VTT = "text/vtt"

    /**
     * The sample MIME type for [fileName]'s subtitle container, or `null` if the name is not
     * a supported side-loadable subtitle. Case-insensitive on the extension; a name with no
     * dot, or a trailing dot, is not a subtitle.
     */
    fun mimeTypeForName(fileName: String): String? =
        when (extensionOf(fileName)) {
            "srt" -> MIME_SUBRIP
            "ssa", "ass" -> MIME_SSA
            "vtt" -> MIME_VTT
            else -> null
        }

    /** True when [fileName] is a subtitle file this viewer can side-load. */
    fun isSubtitle(fileName: String): Boolean = mimeTypeForName(fileName) != null

    /** Lower-cased extension after the last dot, or `""` when there is none (or it is empty). */
    private fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return ""
        return fileName.substring(dot + 1).lowercase()
    }
}
