package com.appblish.calculatorvault.vault

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.media.VideoStoryboard
import com.appblish.calculatorvault.vault.media.VideoStoryboardCache
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * APP-419 DoD — the video thumbnail hide-time pipeline + scrub-preview storyboard, proven on
 * device against an instrumented repository that **counts every decrypt**. Mirrors the photo
 * [ThumbnailCacheDoDTest] acceptance style but for the two APP-419 deliverables:
 *
 * - **P0-B (hide-time pre-generation, encrypted):** hiding a video writes its scrub-preview
 *   storyboard **once** into `previews/`, as ciphertext — never plaintext frames on disk, and it
 *   is produced in the same hide pass (no browse-time full-video frame extraction).
 * - **P0-A (video poster caching, "3rd occurrence" fix):** the poster now resolves through the
 *   exact same [VaultThumbnailPipeline] LRU as every grid tile — visiting A→B→A decrypts the
 *   full blob **zero** times and re-reads A's stored thumb **zero** times (LRU hit).
 * - **P1 (scrub-preview LRU):** [VideoStoryboardCache] decrypts a video's strip from disk **once**
 *   across an A→B→A revisit; every subsequent scrub crops frames from the decoded sheet with no
 *   further decrypt. The decoded [VideoStoryboard.Strip] yields a real frame at 0 / mid / 1.
 */
@RunWith(AndroidJUnit4::class)
class VideoScrubPreviewDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_scrubpreview"
    private val relativePath = "DCIM/CalcVaultScrubDoD/"
    private val displayNames = mutableListOf<String>()

    /** Counts every full-blob decrypt, stored-thumb read, and scrub-strip read the pipelines ask for. */
    private class CountingRepo(
        private val delegate: EncryptedVaultContentRepository,
    ) : VaultContentRepository by delegate {
        val fullDecrypts = AtomicInteger(0)
        val thumbReads = AtomicInteger(0)
        val previewReads = AtomicInteger(0)

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

        override suspend fun openPreviewStrip(itemId: String): ByteArray? {
            previewReads.incrementAndGet()
            return delegate.openPreviewStrip(itemId)
        }
    }

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
        VaultThumbnailPipeline.clear()
        VideoStoryboardCache.clear()
    }

    @After
    fun cleanUp() {
        displayNames.forEach {
            DoDTestSupport.deleteVideoRows(context, it)
        }
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
        VaultThumbnailPipeline.clear()
        VideoStoryboardCache.clear()
    }

    private fun newRepo(): CountingRepo {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        return CountingRepo(repo)
    }

    private fun stagedVideo(index: Int): VaultItem {
        val name = "calcvault_scrub_dod_${System.nanoTime()}_$index.mp4"
        displayNames += name
        val uri =
            DoDTestSupport.insertPublicVideo(context, name, relativePath, DoDTestSupport.synthesizeMp4Bytes(context))
        return VaultItem(
            id = "stagedv$index",
            category = VaultCategory.VIDEOS,
            originalName = name,
            dateLabel = "Today",
            sortKey = System.currentTimeMillis() + index,
            sourceUri = uri.toString(),
            mimeType = "video/mp4",
            relativePath = relativePath,
        )
    }

    private fun previewsDir(): File = File(VaultStorage.vaultDir(context), VaultStorage.PREVIEWS_DIR)

    @Test
    fun hideGeneratesEncryptedStoryboardOncePerVideo() =
        runBlocking<Unit> {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedVideo(1), stagedVideo(2)))
            assertThat(stored).hasSize(2)

            // P0-B: exactly one encrypted preview strip per video, written at hide time.
            val previewFiles = previewsDir().listFiles().orEmpty()
            assertThat(previewFiles).hasLength(2)
            // Each is VaultCrypto ciphertext — NEVER the plaintext "CVSB1" container magic on disk.
            val magic = "CVSB1".toByteArray(Charsets.US_ASCII)
            previewFiles.forEach { f ->
                val head = f.readBytes().copyOfRange(0, magic.size.coerceAtMost(f.length().toInt()))
                assertThat(head).isNotEqualTo(magic)
            }
        }

    @Test
    fun scrubStripDecodesToRealFramesAtEveryPosition() =
        runBlocking<Unit> {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedVideo(1)))
            val id = stored.single().id

            val strip = VideoStoryboardCache.load(id, repo)
            assertThat(strip).isNotNull()
            strip!!
            assertThat(strip.frameCount).isAtLeast(VideoStoryboard.MIN_FRAMES)
            assertThat(strip.frameCount).isAtMost(VideoStoryboard.MAX_FRAMES)
            // A cropped frame exists at the start, middle and end of the timeline.
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val frame = strip.frameAt(fraction)
                assertThat(frame.width).isGreaterThan(0)
                assertThat(frame.height).isGreaterThan(0)
            }
        }

    @Test
    fun revisitDoesNotReDecryptScrubStrip() =
        runBlocking<Unit> {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedVideo(1), stagedVideo(2)))
            val a = stored[0].id
            val b = stored[1].id

            // A → B → A: A's encrypted strip is decrypted from disk exactly once; the revisit is
            // an LRU hit, and B's load never touches A. (Same "no double-decrypt" bar as photos.)
            assertThat(VideoStoryboardCache.load(a, repo)).isNotNull()
            assertThat(repo.previewReads.get()).isEqualTo(1)
            assertThat(VideoStoryboardCache.load(b, repo)).isNotNull()
            assertThat(repo.previewReads.get()).isEqualTo(2)
            assertThat(VideoStoryboardCache.load(a, repo)).isNotNull()
            assertThat(repo.previewReads.get()).isEqualTo(2) // A served from LRU — NOT re-decrypted.

            // Many scrub ticks over the cached strip: still zero further disk reads.
            val strip = VideoStoryboardCache.load(a, repo)!!
            repeat(20) { i -> strip.frameAt(i / 19f) }
            assertThat(repo.previewReads.get()).isEqualTo(2)
        }

    @Test
    fun videoPosterServesFromThumbnailLruWithoutReDecrypting() =
        runBlocking<Unit> {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedVideo(1), stagedVideo(2)))
            val a = stored[0]
            val b = stored[1]

            // P0-A: the pager poster path (VaultThumbnailPipeline.load) over A → B → A. The full
            // video blob is NEVER frame-extracted at browse time (hide-time thumb), and revisiting
            // A is an LRU hit — no second stored-thumb read for A.
            assertThat(VaultThumbnailPipeline.load(context, a, repo)).isNotNull()
            assertThat(VaultThumbnailPipeline.load(context, b, repo)).isNotNull()
            assertThat(VaultThumbnailPipeline.load(context, a, repo)).isNotNull()

            assertThat(repo.fullDecrypts.get()).isEqualTo(0)
            assertThat(repo.thumbReads.get()).isEqualTo(2) // one disk read per distinct item, then cached.
        }

    @Test
    fun deleteEvictsStoredStripAndDecodedStrip() =
        runBlocking<Unit> {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedVideo(1)))
            val item = stored.single()
            assertThat(VideoStoryboardCache.load(item.id, repo)).isNotNull()
            assertThat(previewsDir().listFiles().orEmpty()).hasLength(1)

            repo.moveToRecycleBin(setOf(item.id))
            repo.deleteForever(setOf(item.id))

            // The encrypted strip is gone with the blob, and the decoded strip is evicted.
            assertThat(File(previewsDir(), item.encryptedPath!!).exists()).isFalse()
            assertThat(VideoStoryboardCache.cachedCount()).isEqualTo(0)
        }
}
