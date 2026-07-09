package com.appblish.calculatorvault.vault.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-299 P0-1 — the on-device "swipe-back isn't instant" gap. The VM byte-cache already
 * stops re-*decryption*; this cache stops the *re-decode* of the bitmap that the disposed-
 * page recompose used to re-run. Modelled generically (String stands in for ImageBitmap) so
 * the hit/eviction contract is a plain JVM test.
 *
 * Failing-test-first: on `main @ 2e006c6` there was no bitmap cache, so an A→B→A visit
 * decoded three times; this pins it to two.
 */
class ViewerBitmapCacheTest {
    /** Decode via the cache, counting genuine decodes — mirrors ImagePage's produceState. */
    private fun visit(
        cache: ViewerBitmapCache<String>,
        id: String,
    ): String = cache.getOrPut(id) { "decoded:$id" }

    @Test
    fun `swipe forward then back decodes each page once, not the return again`() {
        val cache = ViewerBitmapCache<String>(maxEntries = 4)
        visit(cache, "A")
        visit(cache, "B")
        visit(cache, "A") // swipe back — must be a cache hit, no third decode

        assertThat(cache.misses).isEqualTo(2)
        assertThat(cache.get("A")).isEqualTo("decoded:A")
    }

    @Test
    fun `an evicted page must be decoded again`() {
        // Cap of 1: viewing B evicts A, so returning to A is a real (third) decode. This
        // documents the bound — the cache is a fast-path, not an unbounded retainer.
        val cache = ViewerBitmapCache<String>(maxEntries = 1)
        visit(cache, "A")
        visit(cache, "B")
        visit(cache, "A")

        assertThat(cache.misses).isEqualTo(3)
    }

    @Test
    fun `access refreshes recency so the active page is never the eviction victim`() {
        val cache = ViewerBitmapCache<String>(maxEntries = 2)
        visit(cache, "A")
        visit(cache, "B")
        // Touch A so it is most-recently-used, then insert C → B (the LRU) is evicted, A stays.
        assertThat(cache.get("A")).isEqualTo("decoded:A")
        visit(cache, "C")

        assertThat(cache.get("A")).isEqualTo("decoded:A")
        assertThat(cache.get("B")).isNull()
        assertThat(cache.get("C")).isEqualTo("decoded:C")
    }

    @Test
    fun `a fresh insert is never evicted by its own overflow`() {
        val cache = ViewerBitmapCache<String>(maxEntries = 1)
        visit(cache, "A")
        visit(cache, "B")
        // B is the just-inserted entry; it must survive, A must go.
        assertThat(cache.get("B")).isEqualTo("decoded:B")
        assertThat(cache.get("A")).isNull()
    }
}
