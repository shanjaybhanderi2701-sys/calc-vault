package com.appblish.playerkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The generic scrub-preview loader for the shared player (APP-419's cache/decode half, moved into
 * the kit by APP-438). It layers exactly two levels:
 *
 * 1. **In-memory LRU** ([ThumbLruCache], byte-bounded) — while the seekbar is being dragged the
 *    same decoded [VideoStoryboard.Strip] is re-served for every frame update with **zero
 *    re-acquisition**: a scrub never re-enters the (possibly expensive) source per drag tick.
 * 2. **The pluggable source** ([StoryboardSource.loadStrip]) — asked at most once per key; the
 *    returned bytes are decoded once via [VideoStoryboard.decode] and cached.
 *
 * There is deliberately **no backfill**: producing a strip means extracting a dozen frames, a job the
 * consumer does ahead of time, not per scrub. An item with no strip (or a decode failure) simply has
 * no preview and the seekbar shows only its time-code bubble.
 *
 * Keys are opaque and supplied by the caller (the surface passes its `pageKey`), so a consumer can
 * namespace them however it needs. Decoded sheets live only in this process-private cache; a consumer
 * that must guarantee eviction (e.g. a secure app leaving its session) calls [clear].
 */
object VideoStoryboardCache {
    /** Small bound — a strip is a scrub aid, only a handful are ever live at once. */
    private val memoryBudgetBytes: Long =
        minOf(Runtime.getRuntime().maxMemory() / 32, 16L * 1024 * 1024).coerceAtLeast(2L * 1024 * 1024)

    private val memory =
        ThumbLruCache<VideoStoryboard.Strip>(memoryBudgetBytes) { strip -> strip.byteSize }

    private val inFlight = ConcurrentHashMap<String, Mutex>()

    /**
     * Load [key]'s decoded storyboard, memory-cache first then [source]. Null → no scrub preview.
     * Concurrent callers for the same [key] coalesce on a per-key [Mutex] so the source is consulted
     * (and the bytes decoded) once.
     */
    suspend fun load(
        key: String,
        source: StoryboardSource,
    ): VideoStoryboard.Strip? {
        memory.get(key)?.let { return it }
        val gate = inFlight.getOrPut(key) { Mutex() }
        return gate.withLock {
            memory.get(key)?.let { return@withLock it }
            val bytes = source.loadStrip() ?: return@withLock null
            val strip = withContext(Dispatchers.IO) { VideoStoryboard.decode(bytes) } ?: return@withLock null
            memory.put(key, strip)
            strip
        }
    }

    /** Drop [key]'s decoded strip (item deleted / invalidated). */
    fun evict(key: String) {
        memory.remove(key)
    }

    /** Drop every decoded strip (e.g. a secure app locking / switching sessions). */
    fun clear() {
        memory.clear()
        inFlight.clear()
    }

    /** Test probe: decoded strips currently held. */
    internal fun cachedCount(): Int = memory.size()
}
