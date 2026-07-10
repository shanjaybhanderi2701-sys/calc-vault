package com.appblish.calculatorvault.vault.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-371 (F4) — pure MIME mapping for external subtitle side-loading. Locks the exact set of
 * supported containers and the sample-MIME each maps to, plus the negative cases so the picker
 * never offers Media3 a format it can't extract.
 */
class SubtitleFormatsTest {
    @Test
    fun srtMapsToSubRip() {
        assertEquals(SubtitleFormats.MIME_SUBRIP, SubtitleFormats.mimeTypeForName("movie.srt"))
    }

    @Test
    fun assAndSsaMapToSsa() {
        assertEquals(SubtitleFormats.MIME_SSA, SubtitleFormats.mimeTypeForName("movie.ass"))
        assertEquals(SubtitleFormats.MIME_SSA, SubtitleFormats.mimeTypeForName("movie.ssa"))
    }

    @Test
    fun vttMapsToWebVtt() {
        assertEquals(SubtitleFormats.MIME_VTT, SubtitleFormats.mimeTypeForName("movie.vtt"))
    }

    @Test
    fun extensionIsCaseInsensitive() {
        assertEquals(SubtitleFormats.MIME_SUBRIP, SubtitleFormats.mimeTypeForName("MOVIE.SRT"))
        assertEquals(SubtitleFormats.MIME_SSA, SubtitleFormats.mimeTypeForName("Show.Ass"))
    }

    @Test
    fun dottedNameUsesLastExtension() {
        assertEquals(SubtitleFormats.MIME_SUBRIP, SubtitleFormats.mimeTypeForName("my.movie.en.srt"))
    }

    @Test
    fun unsupportedOrMalformedNamesAreNotSubtitles() {
        assertNull(SubtitleFormats.mimeTypeForName("movie.mp4"))
        assertNull(SubtitleFormats.mimeTypeForName("movie.sub"))
        assertNull(SubtitleFormats.mimeTypeForName("noextension"))
        assertNull(SubtitleFormats.mimeTypeForName("trailingdot."))
        assertNull(SubtitleFormats.mimeTypeForName(""))
    }

    @Test
    fun isSubtitleReflectsMapping() {
        assertTrue(SubtitleFormats.isSubtitle("a.srt"))
        assertTrue(SubtitleFormats.isSubtitle("a.vtt"))
        assertFalse(SubtitleFormats.isSubtitle("a.mkv"))
    }
}
