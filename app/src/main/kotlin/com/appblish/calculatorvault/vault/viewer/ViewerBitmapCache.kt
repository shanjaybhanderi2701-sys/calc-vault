package com.appblish.calculatorvault.vault.viewer

/**
 * In-session decoded-page cache for the viewer (APP-299 P0-1).
 *
 * The APP-293 fix put a byte-LRU in [PagerViewerViewModel] so swiping back to a viewed page
 * never re-*decrypts*. That fix is intact — but on a real device the swipe-back still wasn't
 * instant: `HorizontalPager` disposes off-screen pages, so the page's `produceState` re-ran
 * the (expensive) `BitmapFactory.decodeByteArray` decode every time the page returned. The
 * owner's ask was literal — *reuse the in-session decrypted **bitmap***.
 *
 * This is a tiny count-capped, access-order LRU keyed by item id that retains the already
 * decoded value ([T] = an `ImageBitmap` on device) so swipe-back reuses it with zero
 * re-decode. It is generic purely so the eviction/hit semantics are unit-testable without a
 * real bitmap. Main-thread only (all callers read/write during composition on the main
 * thread); [misses] counts genuine loads for those tests.
 */
internal class ViewerBitmapCache<T>(
    private val maxEntries: Int,
) {
    // accessOrder = true → get()/put() move the entry to most-recently-used; eviction walks
    // from the least-recently-used end.
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)

    var misses = 0
        private set

    /** The cached value for [id], or null. A hit refreshes recency. */
    fun get(id: String): T? = entries[id]

    /** Store [value] for [id], evicting the least-recently-used entries past the cap. */
    fun put(
        id: String,
        value: T,
    ) {
        entries[id] = value
        if (entries.size <= maxEntries) return
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == id) continue // never evict the entry just inserted
            iterator.remove()
        }
    }

    /**
     * Return the cached value for [id], or run [produce] (counting a miss), cache and return
     * it. The producer is synchronous; the viewer decodes off-main first and only calls this
     * with the finished bitmap, so the cache itself never blocks the main thread.
     */
    fun getOrPut(
        id: String,
        produce: () -> T,
    ): T =
        get(id) ?: produce().also {
            misses++
            put(id, it)
        }
}
