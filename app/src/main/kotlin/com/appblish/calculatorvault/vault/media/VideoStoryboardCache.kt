package com.appblish.calculatorvault.vault.media

import com.appblish.calculatorvault.vault.VaultContentRepository
import com.appblish.calculatorvault.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * APP-419 P1 — the scrub-preview loader for the video player, layered cheapest-first exactly
 * like [VaultThumbnailPipeline] but for the [VideoStoryboard] sprite-sheet:
 *
 * 1. **In-memory LRU** ([ThumbLruCache], byte-bounded) — while the seekbar is being dragged the
 *    same decoded [VideoStoryboard.Strip] is re-served for every frame update with **zero
 *    decryption** (a scrub must never touch the crypto layer per drag tick).
 * 2. **Encrypted on-disk strip** ([VaultContentRepository.openPreviewStrip]) — the storyboard
 *    generated once at hide-time, a few tens of KB to decrypt, decoded via [VideoStoryboard.decode].
 *
 * There is deliberately **no backfill from the full blob**: producing a strip means extracting a
 * dozen frames, which is a hide-time job, not a scrub-time one. A pre-APP-419 video (or an
 * extract failure) simply has no strip and the seekbar shows only its time-code bubble.
 *
 * Security posture matches the thumbnail LRU: decoded sheets live only in this process-private
 * cache, keys are namespaced by [VaultSession.namespace], and [clear] is invoked from the
 * repository's `lock()` so leaving/re-locking the vault drops every decoded frame.
 */
object VideoStoryboardCache {
    /** Small bound — a strip is a scrub aid, only a handful are ever live at once. */
    private val memoryBudgetBytes: Long =
        minOf(Runtime.getRuntime().maxMemory() / 32, 16L * 1024 * 1024).coerceAtLeast(2L * 1024 * 1024)

    private val memory =
        ThumbLruCache<VideoStoryboard.Strip>(memoryBudgetBytes) { strip -> strip.byteSize }

    private val inFlight = ConcurrentHashMap<String, Mutex>()

    /** Load [itemId]'s decoded storyboard, cheapest source first. Null → no scrub preview. */
    suspend fun load(
        itemId: String,
        repository: VaultContentRepository,
    ): VideoStoryboard.Strip? {
        val key = cacheKey(itemId)
        memory.get(key)?.let { return it }
        val gate = inFlight.getOrPut(key) { Mutex() }
        return gate.withLock {
            memory.get(key)?.let { return@withLock it }
            val bytes = repository.openPreviewStrip(itemId) ?: return@withLock null
            val strip = withContext(Dispatchers.IO) { VideoStoryboard.decode(bytes) } ?: return@withLock null
            memory.put(key, strip)
            strip
        }
    }

    /** Drop [itemId]'s decoded strip (delete / restore invalidation). */
    fun evict(itemId: String) {
        memory.remove(cacheKey(itemId))
    }

    /** Drop every decoded strip (vault lock / decoy switch). */
    fun clear() {
        memory.clear()
        inFlight.clear()
    }

    /** Test probe: decoded strips currently held. */
    internal fun cachedCount(): Int = memory.size()

    private fun cacheKey(itemId: String): String = "${VaultSession.namespace}|$itemId"
}
