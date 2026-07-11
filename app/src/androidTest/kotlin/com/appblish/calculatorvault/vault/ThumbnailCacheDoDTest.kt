package com.appblish.calculatorvault.vault

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * APP-244 board QA note: the pre-fix suite only proved hide-then-read *once*; the owner's
 * device showed every scroll/back/reopen re-decrypting everything. This test drives the
 * exact repeat-visit pattern through fresh [CategoryViewModel] instances (what re-entering
 * the screen creates) over an instrumented repository that **counts decrypts**, and
 * asserts the board's acceptance bar mechanically:
 *
 * - Hide N items → first grid visit renders every thumbnail **without a single full-blob
 *   decrypt** (hide-time encrypted thumbs, decrypted-tiny-not-full).
 * - Navigate away and back, reopen, repeatedly → later visits render entirely from the
 *   in-memory LRU: **zero further decrypts of any kind**.
 * - Thumbs are stored **encrypted** on disk (no plaintext JPEG in the vault dir).
 * - Delete and restore (un-hide) evict both cache layers.
 * - Items hidden before APP-244 (no stored thumb) backfill **once**, then serve cached.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailCacheDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_thumbcache"
    private val relativePath = "DCIM/CalcVaultThumbDoD/"
    private val displayNames = mutableListOf<String>()

    /** Counts every full-blob and stored-thumb decrypt the pipeline asks for. */
    private class CountingRepo(
        private val delegate: EncryptedVaultContentRepository,
    ) : VaultContentRepository by delegate {
        val fullDecrypts = AtomicInteger(0)
        val thumbReads = AtomicInteger(0)

        override suspend fun openDecrypted(itemId: String): ByteArray? {
            fullDecrypts.incrementAndGet()
            return delegate.openDecrypted(itemId)
        }

        override suspend fun decryptToFile(
            itemId: String,
            dest: File,
        ): Boolean {
            fullDecrypts.incrementAndGet()
            return delegate.decryptToFile(itemId, dest)
        }

        override suspend fun openThumbnail(itemId: String): ByteArray? {
            thumbReads.incrementAndGet()
            return delegate.openThumbnail(itemId)
        }
    }

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
        VaultThumbnailPipeline.clear()
    }

    @After
    fun cleanUp() {
        displayNames.forEach {
            DoDTestSupport.deleteImageRows(context, it)
            DoDTestSupport.deleteVideoRows(context, it)
        }
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
        VaultThumbnailPipeline.clear()
    }

    private fun newRepo(): CountingRepo {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        return CountingRepo(repo)
    }

    private fun stagedImage(index: Int): VaultItem {
        val name = "calcvault_thumb_dod_${System.nanoTime()}_$index.jpg"
        displayNames += name
        val uri =
            DoDTestSupport.insertPublicImage(context, name, relativePath, DoDTestSupport.sampleJpegBytes())
        return VaultItem(
            id = "staged$index",
            category = VaultCategory.PHOTOS,
            originalName = name,
            dateLabel = "Today",
            sortKey = System.currentTimeMillis() + index,
            sourceUri = uri.toString(),
            mimeType = "image/jpeg",
            relativePath = relativePath,
        )
    }

    private fun stagedVideo(index: Int = 0): VaultItem {
        val name = "calcvault_thumb_dod_${System.nanoTime()}_v$index.mp4"
        displayNames += name
        val uri =
            DoDTestSupport.insertPublicVideo(context, name, relativePath, DoDTestSupport.synthesizeMp4Bytes(context))
        return VaultItem(
            id = "stagedv$index",
            category = VaultCategory.VIDEOS,
            originalName = name,
            dateLabel = "Today",
            sortKey = System.currentTimeMillis(),
            sourceUri = uri.toString(),
            mimeType = "video/mp4",
            relativePath = relativePath,
        )
    }

    /** One "grid visit": a fresh ViewModel (as re-entering the screen creates) loads every tile. */
    private suspend fun visitGrid(
        repo: CountingRepo,
        category: VaultCategory,
    ): Long {
        val vm = CategoryViewModel(category, repo)
        val items = vm.state.first { it.items.isNotEmpty() }.items
        val start = System.currentTimeMillis()
        items.forEach { item ->
            assertThat(vm.thumbnail(context, item.id)).isNotNull()
        }
        return System.currentTimeMillis() - start
    }

    @Test
    fun repeatedGridVisitsServeThumbnailsFromCacheWithoutReDecrypting() =
        runBlocking<Unit> {
            val repo = newRepo()
            val staged = (1..6).map { stagedImage(it) } + stagedVideo()
            val stored = repo.hide(staged)
            assertThat(stored).hasSize(7)

            // Hide-time thumbs exist, and they are ciphertext on disk — never plaintext JPEG.
            val thumbsDir = File(VaultStorage.vaultDir(context), VaultStorage.THUMBS_DIR)
            val thumbFiles = thumbsDir.listFiles().orEmpty()
            assertThat(thumbFiles).hasLength(7)
            thumbFiles.forEach { f ->
                assertThat(f.readBytes().copyOfRange(0, 3)).isNotEqualTo(DoDTestSupport.JPEG_MAGIC)
            }

            // Visit 1 (cold): every tile renders, and NOT ONE full blob is decrypted —
            // the grid decrypts only the tiny stored thumbs (board fix #2).
            val cold = visitGrid(repo, VaultCategory.PHOTOS) + visitGrid(repo, VaultCategory.VIDEOS)
            assertThat(repo.fullDecrypts.get()).isEqualTo(0)
            val thumbReadsAfterCold = repo.thumbReads.get()
            assertThat(thumbReadsAfterCold).isEqualTo(7)

            // Visits 2..4 (navigate away and back, reopen, repeatedly — fresh ViewModels):
            // every tile still renders, from the in-memory LRU — zero further decrypts of
            // any kind, neither full blobs nor stored thumbs.
            var warm = 0L
            repeat(3) {
                warm = visitGrid(repo, VaultCategory.PHOTOS) + visitGrid(repo, VaultCategory.VIDEOS)
            }
            assertThat(repo.fullDecrypts.get()).isEqualTo(0)
            assertThat(repo.thumbReads.get()).isEqualTo(thumbReadsAfterCold)
            Log.i("ThumbnailCacheDoD", "grid visit timing: cold=${cold}ms warmRevisit=${warm}ms")
        }

    /**
     * APP-430 (APP-417 R6) — the video **preview pager** poster path. The restored preview state
     * loads each video's large poster through the exact same [VaultThumbnailPipeline.load] the pager
     * uses (settled page + the n±1 warm), NEVER an on-demand full-video frame extraction. This
     * asserts the owner's decrypt-count requirement verbatim: browsing A → B → back to A must serve
     * A's poster from the in-memory LRU with **zero re-decode** (and never a full-blob decrypt).
     */
    @Test
    fun videoPreviewPager_AtoBtoA_servesPosterFromCacheWithoutReDecoding() =
        runBlocking<Unit> {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedVideo(1), stagedVideo(2)))
            assertThat(stored).hasSize(2)
            val a = stored[0]
            val b = stored[1]

            // Visit A (cold): its pre-generated encrypted thumb is decrypted once — no full video.
            assertThat(VaultThumbnailPipeline.load(context, a, repo)).isNotNull()
            assertThat(repo.fullDecrypts.get()).isEqualTo(0)
            assertThat(repo.thumbReads.get()).isEqualTo(1)

            // Swipe to B (cold): its own thumb decrypts once — A stays warm in the LRU.
            assertThat(VaultThumbnailPipeline.load(context, b, repo)).isNotNull()
            assertThat(repo.fullDecrypts.get()).isEqualTo(0)
            assertThat(repo.thumbReads.get()).isEqualTo(2)

            // Swipe BACK to A: LRU hit — no stored-thumb read, no full decrypt (owner's A→B→A bar).
            assertThat(VaultThumbnailPipeline.load(context, a, repo)).isNotNull()
            assertThat(repo.fullDecrypts.get()).isEqualTo(0)
            assertThat(repo.thumbReads.get()).isEqualTo(2)
        }

    @Test
    fun deleteAndRestoreEvictStoredThumbsAndCachedTiles() =
        runBlocking<Unit> {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedImage(1), stagedImage(2)))
            assertThat(stored).hasSize(2)
            visitGrid(repo, VaultCategory.PHOTOS)

            val thumbsDir = File(VaultStorage.vaultDir(context), VaultStorage.THUMBS_DIR)
            assertThat(thumbsDir.listFiles().orEmpty()).hasLength(2)

            // Permanent delete drops the item's encrypted thumb with its blob.
            val deleted = stored[0]
            repo.moveToRecycleBin(setOf(deleted.id))
            repo.deleteForever(setOf(deleted.id))
            assertThat(File(thumbsDir, deleted.encryptedPath!!).exists()).isFalse()

            // Restore (un-hide) drops it too — nothing cached for content no longer hidden.
            val restored = stored[1]
            val summary = repo.unhideDetailed(setOf(restored.id))
            assertThat(summary.restored).isEqualTo(1)
            assertThat(File(thumbsDir, restored.encryptedPath!!).exists()).isFalse()
            assertThat(thumbsDir.listFiles().orEmpty()).isEmpty()
        }

    @Test
    fun preAppFixItemsBackfillOnceThenServeFromCaches() =
        runBlocking<Unit> {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedImage(1), stagedImage(2)))
            assertThat(stored).hasSize(2)

            // Simulate a vault hidden by a pre-APP-244 build: no stored thumbs on disk.
            val thumbsDir = File(VaultStorage.vaultDir(context), VaultStorage.THUMBS_DIR)
            thumbsDir.listFiles().orEmpty().forEach { it.delete() }
            VaultThumbnailPipeline.clear()

            // Visit 1: renders via backfill — exactly one full decrypt per item — and
            // regenerates the encrypted stored thumbs.
            visitGrid(repo, VaultCategory.PHOTOS)
            assertThat(repo.fullDecrypts.get()).isEqualTo(2)
            assertThat(thumbsDir.listFiles().orEmpty()).hasLength(2)

            // Drop only the memory layer (fresh app process would): the regenerated disk
            // thumbs serve the next visit — still no further full decrypts.
            VaultThumbnailPipeline.clear()
            visitGrid(repo, VaultCategory.PHOTOS)
            assertThat(repo.fullDecrypts.get()).isEqualTo(2)
        }
}
