package com.appblish.calculatorvault.vault.media

/**
 * A minimal byte-bounded LRU map for decoded thumbnails (APP-244 cache management).
 *
 * Pure JVM (a [LinkedHashMap] in access order, no android.util.LruCache) so the memory
 * bound and eviction order are unit-testable off-device. All operations are synchronized —
 * the grid decodes tiles on many IO workers at once.
 *
 * [maxBytes] caps the *sum of [sizeOf] over the values*; inserting past the cap evicts
 * least-recently-used entries first, so revisiting a 30-item grid keeps every tile while a
 * 1000-item vault can never OOM the heap.
 */
internal class ThumbLruCache<V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Int,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    private val map = LinkedHashMap<String, V>(16, 0.75f, true)
    private var currentBytes = 0L

    @Synchronized
    fun get(key: String): V? = map[key]

    @Synchronized
    fun put(
        key: String,
        value: V,
    ) {
        val size = sizeOf(value).toLong()
        // A single value larger than the whole cache would evict everything and still not
        // fit; refuse it rather than thrash (callers just fall back to uncached decode).
        if (size > maxBytes) return
        map.remove(key)?.let { currentBytes -= sizeOf(it) }
        map[key] = value
        currentBytes += size
        val eldest = map.entries.iterator()
        while (currentBytes > maxBytes && eldest.hasNext()) {
            val entry = eldest.next()
            currentBytes -= sizeOf(entry.value)
            eldest.remove()
        }
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)?.let { currentBytes -= sizeOf(it) }
    }

    @Synchronized
    fun clear() {
        map.clear()
        currentBytes = 0L
    }

    @Synchronized
    fun snapshotBytes(): Long = currentBytes

    @Synchronized
    fun size(): Int = map.size
}
