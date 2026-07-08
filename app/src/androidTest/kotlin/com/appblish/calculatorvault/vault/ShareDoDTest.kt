package com.appblish.calculatorvault.vault

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.share.VaultShare
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

/**
 * APP-294 Share — instrumented proof of the mandatory temp-copy security contract, item
 * by item (owner fix pass P2 item 8):
 *
 *  1. the share copy is a **decrypt into a scoped `cacheDir/share/<session>/` dir**, named
 *     by the item's original name (never the vault's UUID blob name);
 *  2. receivers see only an opaque **FileProvider URI** that round-trips the plaintext —
 *     the URI never encodes the vault's real storage path or the encrypted blob;
 *  3. **purge on completion/cancel** ([VaultShare.purge]) removes the copy and kills the
 *     URI, and **purge on process restart** ([VaultShare.purgeAll]) sweeps leftovers;
 *  4. the share intent carries a **read-only** grant;
 *  5. a **large video streams** through the APP-244 chunked path (no OOM).
 */
@RunWith(AndroidJUnit4::class)
class ShareDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_share"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultShare.purgeAll(context)
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        VaultShare.purgeAll(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun shareCopyIsScopedProviderServedAndPurgedOnFinish() =
        runBlocking {
            val repo = unlockedRepo()
            val original = DoDTestSupport.sampleJpegBytes()
            val stored = hideBytes(repo, "dod share photo.jpg", "image/jpeg", original)

            val session = VaultShare.prepare(context, repo, listOf(stored))
            assertThat(session).isNotNull()
            session!!

            // 1. Scoped temp copy: inside cacheDir/share/<session>/, carrying the item's
            //    display name — the decrypted bytes, not the ciphertext.
            val shareRoot = File(context.cacheDir, "share")
            assertThat(session.dir.parentFile).isEqualTo(shareRoot)
            val copy = File(session.dir, "dod share photo.jpg")
            assertThat(copy.exists()).isTrue()
            assertThat(copy.readBytes()).isEqualTo(original)

            // 2. Opaque provider URI: right authority, plaintext round-trip through the
            //    resolver (what a receiver does), and no vault internals in the URI —
            //    neither the blob's UUID name nor the public .CalcVault/ folder.
            val uri = session.uris.single()
            assertThat(uri.scheme).isEqualTo("content")
            assertThat(uri.authority).isEqualTo("${context.packageName}.share")
            assertThat(uri.toString()).doesNotContain(stored.encryptedPath!!)
            assertThat(uri.toString()).doesNotContain(".CalcVault")
            val served =
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertThat(served).isEqualTo(original)

            // The encrypted blob itself is untouched and still ciphertext on disk.
            val blob = VaultStorage.blobFile(context, stored.encryptedPath!!)
            assertThat(blob.exists()).isTrue()
            blob.inputStream().use { stream ->
                val head = ByteArray(3)
                assertThat(stream.read(head)).isEqualTo(3)
                assertThat(head).isNotEqualTo(DoDTestSupport.JPEG_MAGIC)
            }

            // 3. Purge on completion/cancel: the copy is gone and the URI dies with it.
            VaultShare.purge(session)
            assertThat(session.dir.exists()).isFalse()
            assertThat(runCatching { context.contentResolver.openInputStream(uri) }.exceptionOrNull())
                .isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    fun chooserIntentSendsReadOnlyProviderUris() =
        runBlocking {
            val repo = unlockedRepo()
            val bytes = DoDTestSupport.sampleJpegBytes()
            val a = hideBytes(repo, "pic.jpg", "image/jpeg", bytes)
            val b = hideBytes(repo, "pic.jpg", "image/jpeg", bytes)

            val session = VaultShare.prepare(context, repo, listOf(a, b))
            assertThat(session).isNotNull()
            session!!

            // Duplicate display names are de-duplicated inside the session.
            assertThat(session.dir.list()!!.toSet()).containsExactly("pic.jpg", "pic (2).jpg")

            val chooser = VaultShare.chooserIntent(session)
            assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertThat(send.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
            assertThat(send.type).isEqualTo("image/*")
            val streams = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertThat(streams).containsExactlyElementsIn(session.uris)

            // Read-only grant: receivers can read the temp copy, never write it (4).
            assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
            assertThat(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
            assertThat(send.clipData!!.itemCount).isEqualTo(2)

            VaultShare.purge(session)
        }

    @Test
    fun purgeAllSweepsCrashLeftoversAtProcessRestart() {
        val leftover = File(File(context.cacheDir, "share"), "stale-session/orphan.jpg")
        leftover.parentFile!!.mkdirs()
        leftover.writeBytes(byteArrayOf(1, 2, 3))
        assertThat(leftover.exists()).isTrue()

        // What CalculatorVaultApp.onCreate runs before any UI: no share copy survives
        // a crash/force-kill into the next app session (3).
        VaultShare.purgeAll(context)
        assertThat(File(context.cacheDir, "share").exists()).isFalse()
    }

    @Test
    fun largeVideoShareStreamsWithoutMaterializing() =
        runBlocking {
            val repo = unlockedRepo()
            // Multi-hundred-chunk payload (32 MB >> the 512 KB crypto chunk) with a
            // recognizable pattern — big enough that a whole-file buffering regression
            // would be visible in CI heap churn, small enough to keep the matrix fast
            // (the 300 MB heap-buster is LargeVideoHideDoDTest's job; share reuses the
            // same decryptToFile streaming seam it proves).
            val sizeBytes = 32 * 1024 * 1024
            val slice = ByteArray(1024 * 1024) { (it % 251).toByte() }
            val source = File(context.cacheDir, "dod_share_video.mp4")
            source.outputStream().use { out -> repeat(sizeBytes / slice.size) { out.write(slice) } }

            val stored =
                repo
                    .hide(
                        listOf(
                            VaultItem(
                                id = "staged_share_video",
                                category = VaultCategory.VIDEOS,
                                originalName = "dod_share_video.mp4",
                                dateLabel = "Today",
                                sortKey = System.currentTimeMillis(),
                                sourceUri = "file://${source.absolutePath}",
                                mimeType = "video/mp4",
                                relativePath = "Movies/",
                            ),
                        ),
                    ).single()
            source.delete()

            val session = VaultShare.prepare(context, repo, listOf(stored))
            assertThat(session).isNotNull()
            session!!
            val copy = File(session.dir, "dod_share_video.mp4")
            assertThat(copy.length()).isEqualTo(sizeBytes.toLong())
            copy.inputStream().use { stream ->
                val head = ByteArray(16)
                assertThat(stream.read(head)).isEqualTo(16)
                assertThat(head).isEqualTo(slice.copyOfRange(0, 16))
            }
            assertThat(session.mimeType).isEqualTo("video/mp4")

            VaultShare.purge(session)
            assertThat(session.dir.exists()).isFalse()
        }

    // --- fixtures ----------------------------------------------------------------------

    private fun unlockedRepo(): EncryptedVaultContentRepository {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        return repo
    }

    /** Stage [bytes] as a cache file and hide it, returning the stored vault item. */
    private suspend fun hideBytes(
        repo: EncryptedVaultContentRepository,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): VaultItem {
        val staged = File(context.cacheDir, "staged_${System.nanoTime()}.bin")
        staged.writeBytes(bytes)
        return repo
            .hide(
                listOf(
                    VaultItem(
                        id = "staged_${System.nanoTime()}",
                        category = VaultCategory.PHOTOS,
                        originalName = displayName,
                        dateLabel = "Today",
                        sortKey = System.currentTimeMillis(),
                        sourceUri = "file://${staged.absolutePath}",
                        mimeType = mimeType,
                        relativePath = "DCIM/",
                    ),
                ),
            ).single()
            .also { staged.delete() }
    }
}
