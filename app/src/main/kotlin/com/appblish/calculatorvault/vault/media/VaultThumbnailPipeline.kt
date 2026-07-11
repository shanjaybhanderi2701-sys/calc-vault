package com.appblish.calculatorvault.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.appblish.calculatorvault.vault.VaultContentRepository
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The vault grids' thumbnail loader (APP-244 perf pass). Every tile — category folder
 * covers, in-folder grids, the home Recent strip — resolves through here, layered
 * cheapest-first:
 *
 * 1. **In-memory LRU** ([ThumbLruCache], byte-bounded to [memoryBudgetBytes]) — revisiting
 *    a grid or navigating back re-serves decoded tiles instantly with **zero decryption**.
 * 2. **Encrypted on-disk thumb** ([VaultContentRepository.openThumbnail]) — a ~200px JPEG
 *    written at hide-time, a few tens of KB to decrypt instead of a full photo/video.
 *    Never plaintext on disk.
 * 3. **Backfill** — items hidden before APP-244 have no stored thumb; decode one from the
 *    full blob exactly once (images via a sampled in-memory decode; videos via a
 *    streaming-decrypted temp file so a large video never materializes as a ByteArray),
 *    persist it encrypted ([VaultContentRepository.saveThumbnail]), then serve from the
 *    caches forever after.
 *
 * Concurrency: a per-key [Mutex] collapses simultaneous requests for the same item (a
 * folder cover and the Recent strip often race for one id), while distinct items decode
 * in parallel on IO workers — the UI thread never blocks and tiles appear progressively.
 *
 * Security posture: decoded thumbnails live only in this process-private LRU; [clear] is
 * invoked from the repository's `lock()` so locking the vault (leave, re-lock, decoy
 * switch) drops every decoded pixel. Keys are namespaced with [VaultSession.namespace] so
 * a decoy session can never be served the real vault's tiles even mid-transition.
 */
object VaultThumbnailPipeline {
    private const val TAG = "VaultThumbs"

    /** Memory bound: an eighth of the heap, capped at 48 MB (~1000 200px ARGB tiles). */
    private val memoryBudgetBytes: Long =
        minOf(Runtime.getRuntime().maxMemory() / 8, 48L * 1024 * 1024).coerceAtLeast(4L * 1024 * 1024)

    private val memory =
        ThumbLruCache<ImageBitmap>(memoryBudgetBytes) { bitmap -> bitmap.width * bitmap.height * 4 }

    /**
     * Separate memory bound for the LARGE pager posters (APP-435). A poster is ~1080px so each
     * decoded bitmap is a few MB — a handful is plenty for the settled page + its n±1 neighbours,
     * and keeping them out of the small-thumb LRU means one big poster can't evict a grid-full of
     * tiles. A sixth of the heap, capped at 64 MB, floored at 32 MB so A→B→A + n±1 all stay warm.
     */
    private val posterBudgetBytes: Long =
        minOf(Runtime.getRuntime().maxMemory() / 6, 64L * 1024 * 1024).coerceAtLeast(32L * 1024 * 1024)

    private val posterMemory =
        ThumbLruCache<ImageBitmap>(posterBudgetBytes) { bitmap -> bitmap.width * bitmap.height * 4 }

    private val inFlight = ConcurrentHashMap<String, Mutex>()

    /** Load the grid thumbnail for [item], cheapest source first. Null → placeholder glyph. */
    suspend fun load(
        context: Context,
        item: VaultItem,
        repository: VaultContentRepository,
    ): ImageBitmap? {
        val key = cacheKey(item.id)
        memory.get(key)?.let { return it }
        val gate = inFlight.getOrPut(key) { Mutex() }
        return gate.withLock {
            // Re-check: a racing loader may have populated the cache while we waited.
            memory.get(key)?.let { return@withLock it }
            val start = System.nanoTime()
            val fromDisk = loadStoredThumb(item, repository)
            if (fromDisk != null) {
                memory.put(key, fromDisk)
                logTiming("disk-thumb", item.id, start)
                return@withLock fromDisk
            }
            val backfilled = backfill(context, item, repository)
            if (backfilled != null) {
                memory.put(key, backfilled)
                logTiming("backfill", item.id, start)
            }
            backfilled
        }
    }

    /**
     * Load the LARGE pager poster for a video [item] (APP-435) — the sharp near-full-screen
     * frame the video pager renders, distinct from the small grid thumb served by [load].
     * Cheapest source first, mirroring [load]'s guarantees:
     *
     * 1. **Poster LRU** — a settled-page/n±1 revisit (A→B→A) is a cache hit with **zero decrypt**.
     * 2. **Encrypted on-disk poster** ([VaultContentRepository.openPoster]) — the ~1080px JPEG
     *    written at hide-time. Served as-is when it meets [VaultThumbnails.POSTER_MIN_ACCEPT_PX];
     *    an old icon-sized poster below that is treated as stale and regenerated (migration).
     * 3. **Backfill** — a video hidden before APP-435 (no stored poster) or with a stale one:
     *    stream-decrypt the blob to a temp file exactly once, extract a full-res frame, scale to
     *    [VaultThumbnails.POSTER_PX], persist it encrypted, then serve from the cache forever after.
     *
     * Null → the caller falls back to the small grid thumb (still sharper than a blank canvas).
     */
    suspend fun loadPoster(
        context: Context,
        item: VaultItem,
        repository: VaultContentRepository,
    ): ImageBitmap? {
        if (item.category != VaultCategory.VIDEOS) return null
        val key = posterKey(item.id)
        posterMemory.get(key)?.let { return it }
        val gate = inFlight.getOrPut(key) { Mutex() }
        return gate.withLock {
            posterMemory.get(key)?.let { return@withLock it }
            val start = System.nanoTime()
            val storedJpeg = repository.openPoster(item.id)
            if (storedJpeg != null &&
                VaultThumbnails.posterLongestEdge(storedJpeg) >= VaultThumbnails.POSTER_MIN_ACCEPT_PX
            ) {
                val decoded = withContext(Dispatchers.IO) { VaultThumbnails.decodeStoredThumb(storedJpeg) }
                if (decoded != null) {
                    posterMemory.put(key, decoded)
                    logTiming("disk-poster", item.id, start)
                    return@withLock decoded
                }
            }
            val backfilled = backfillPoster(context, item, repository)
            if (backfilled != null) {
                posterMemory.put(key, backfilled)
                logTiming("backfill-poster", item.id, start)
            }
            backfilled
        }
    }

    /**
     * Regenerate the large poster from the encrypted blob for a pre-APP-435 video (or one whose
     * stored poster is icon-sized), persist it encrypted, and return it. Streams the blob to a
     * temp file so a large video never materializes as a ByteArray — same discipline as [backfill].
     */
    private suspend fun backfillPoster(
        context: Context,
        item: VaultItem,
        repository: VaultContentRepository,
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "poster_${UUID.randomUUID()}")
            val frame =
                try {
                    if (repository.decryptToFile(
                            item.id,
                            tmp
                        )
                    ) {
                        VaultThumbnails.videoFrameFromFile(tmp.absolutePath)
                    } else {
                        null
                    }
                } finally {
                    tmp.delete()
                }
            frame?.let { decoded ->
                val oriented = VaultThumbnails.rotate(decoded, item.rotationDegrees)
                runCatching { repository.savePoster(item.id, VaultThumbnails.toStoredPosterJpeg(oriented)) }
                oriented.asImageBitmap()
            }
        }

    /** Drop [itemId]'s decoded tile + poster (delete / restore invalidation). */
    fun evict(itemId: String) {
        memory.remove(cacheKey(itemId))
        posterMemory.remove(posterKey(itemId))
    }

    /** Drop every decoded tile and poster (vault lock / decoy switch). */
    fun clear() {
        memory.clear()
        posterMemory.clear()
        inFlight.clear()
    }

    /** Test probe: decoded tiles currently held. */
    internal fun cachedCount(): Int = memory.size()

    /** Test probe: decoded posters currently held. */
    internal fun cachedPosterCount(): Int = posterMemory.size()

    private fun cacheKey(itemId: String): String = "${VaultSession.namespace}|$itemId"

    private fun posterKey(itemId: String): String = "${VaultSession.namespace}|poster|$itemId"

    private suspend fun loadStoredThumb(
        item: VaultItem,
        repository: VaultContentRepository,
    ): ImageBitmap? {
        val jpeg = repository.openThumbnail(item.id) ?: return null
        return withContext(Dispatchers.IO) { VaultThumbnails.decodeStoredThumb(jpeg) }
    }

    /**
     * Decode a tile from the full blob for a pre-APP-244 item, then persist it as an
     * encrypted stored thumb so this cost is paid at most once per item.
     */
    private suspend fun backfill(
        context: Context,
        item: VaultItem,
        repository: VaultContentRepository,
    ): ImageBitmap? {
        val isImage =
            item.category == VaultCategory.PHOTOS || item.mimeType?.startsWith("image/") == true
        val isVideo = item.category == VaultCategory.VIDEOS
        if (!isImage && !isVideo) return null
        return withContext(Dispatchers.IO) {
            val bitmap: Bitmap? =
                if (isImage) {
                    repository.openDecrypted(item.id)?.let(VaultThumbnails::sampledBitmapFromBytes)
                } else {
                    // Stream-decrypt to a temp file: a large video never becomes a ByteArray.
                    val tmp = File(context.cacheDir, "backfill_${UUID.randomUUID()}")
                    try {
                        if (repository.decryptToFile(item.id, tmp)) {
                            VaultThumbnails.videoFrameFromFile(tmp.absolutePath)
                        } else {
                            null
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            bitmap?.let { decoded ->
                // A persisted rotation (W3-E) must reach every cached-thumbnail consumer:
                // bake it into the backfilled thumb so disk + LRU agree with the index.
                val oriented = VaultThumbnails.rotate(decoded, item.rotationDegrees)
                runCatching { repository.saveThumbnail(item.id, VaultThumbnails.toStoredJpeg(oriented)) }
                oriented.asImageBitmap()
            }
        }
    }

    private fun logTiming(
        source: String,
        itemId: String,
        startNanos: Long,
    ) {
        Log.d(TAG, "$source $itemId in ${(System.nanoTime() - startNanos) / 1_000_000}ms")
    }
}
