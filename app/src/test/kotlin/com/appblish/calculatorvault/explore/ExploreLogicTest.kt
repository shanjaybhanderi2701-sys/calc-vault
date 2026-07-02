package com.appblish.calculatorvault.explore

import com.appblish.calculatorvault.explore.browser.toUrl
import com.appblish.calculatorvault.explore.junk.JunkCategory
import com.appblish.calculatorvault.explore.junk.JunkCleanerState
import com.appblish.calculatorvault.explore.junk.JunkPhase
import com.appblish.calculatorvault.explore.junk.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the Explore tools' pure logic — domain normalization + blocklist
 * matching, junk-selection math, note upsert, and the browser's address→URL rule. UI is
 * verified on-device; this pins the behavior the screens depend on.
 */
class ExploreLogicTest {
    @Test
    fun `normalizeDomain strips scheme, www and path`() {
        assertEquals("example.com", ExploreStore.normalizeDomain("https://www.example.com/path?q=1"))
        assertEquals("news.ycombinator.com", ExploreStore.normalizeDomain("HTTP://news.ycombinator.com"))
    }

    @Test
    fun `isBlocked matches host and subdomains only when enabled`() {
        // Start clean, add a site, verify subdomain match, disable, verify miss.
        ExploreStore.blockedSites.value.forEach { ExploreStore.removeBlockedSite(it.id) }
        assertTrue(ExploreStore.addBlockedSite("facebook.com"))
        assertTrue(ExploreStore.isBlocked("facebook.com"))
        assertTrue(ExploreStore.isBlocked("m.facebook.com"))
        assertFalse(ExploreStore.isBlocked("notfacebook.com"))

        val site = ExploreStore.blockedSites.value.first { it.domain == "facebook.com" }
        ExploreStore.setBlockedEnabled(site.id, false)
        assertFalse(ExploreStore.isBlocked("facebook.com"))
        ExploreStore.removeBlockedSite(site.id)
    }

    @Test
    fun `addBlockedSite rejects invalid and duplicate entries`() {
        ExploreStore.blockedSites.value.forEach { ExploreStore.removeBlockedSite(it.id) }
        assertFalse(ExploreStore.addBlockedSite("notadomain"))
        assertTrue(ExploreStore.addBlockedSite("example.org"))
        assertFalse(ExploreStore.addBlockedSite("www.example.org")) // normalizes to a dup
    }

    @Test
    fun `junk selection sums only checked buckets`() {
        val state =
            JunkCleanerState(
                phase = JunkPhase.RESULTS,
                categories =
                    listOf(
                        JunkCategory("a", "A", 100, selected = true),
                        JunkCategory("b", "B", 40, selected = false),
                        JunkCategory("c", "C", 10, selected = true),
                    ),
            )
        assertEquals(110L, state.selectedBytes)
        assertEquals(150L, state.totalBytes)
        assertTrue(state.canClean)
    }

    @Test
    fun `formatBytes renders human units`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
    }

    @Test
    fun `upsertNote creates then updates in place`() {
        val id = ExploreStore.upsertNote(null, "Title", "Body", now = 1L)
        assertTrue(ExploreStore.notes.value.any { it.id == id && it.title == "Title" })
        val same = ExploreStore.upsertNote(id, "Renamed", "Body2", now = 2L)
        assertEquals(id, same)
        val note = ExploreStore.notes.value.first { it.id == id }
        assertEquals("Renamed", note.title)
        ExploreStore.deleteNote(id)
        assertFalse(ExploreStore.notes.value.any { it.id == id })
    }

    @Test
    fun `blank note title falls back to Untitled`() {
        val id = ExploreStore.upsertNote(null, "   ", "body", now = 3L)
        assertEquals(
            "Untitled note",
            ExploreStore.notes.value
                .first { it.id == id }
                .title
        )
        ExploreStore.deleteNote(id)
    }

    @Test
    fun `toUrl adds https to bare domains and preserves full urls`() {
        // The search-fallback branch calls android.net.Uri and is verified on-device;
        // here we pin the two pure branches the browser relies on.
        assertEquals("https://example.com", toUrl("example.com"))
        assertEquals("https://example.com", toUrl("https://example.com"))
        assertEquals("http://example.com", toUrl("http://example.com"))
    }
}
