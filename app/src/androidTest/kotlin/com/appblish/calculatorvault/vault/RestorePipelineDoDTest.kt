package com.appblish.calculatorvault.vault

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-1 DoD proof for the **restore (un-hide) pipeline** (APP-225, build spec §8/§11):
 * a hidden photo restored via [VaultContentRepository.unhideDetailed] returns to its
 * original public RELATIVE_PATH byte-for-byte and MediaStore indexes it again; when the
 * original location is unknown it lands in the always-visible **Downloads** fallback
 * (W1-E2 §7 destination contract, ratified in APP-259 — supersedes design call D-3's
 * per-category `DCIM/Restored/`; restore still never fails silently). Verified entirely
 * with in-process MediaStore content queries on the CI instrumented matrix — no manual
 * emulator screenshots (board hard rule #2).
 */
@RunWith(AndroidJUnit4::class)
class RestorePipelineDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_restore"
    private val originalName = "calcvault_dod_restore_${System.nanoTime()}.jpg"
    private val fallbackName = "calcvault_dod_fallback_${System.nanoTime()}.jpg"
    private val relativePath = "DCIM/CalcVaultDoD/"
    private lateinit var repo: EncryptedVaultContentRepository

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        DoDTestSupport.deleteImageRows(context, originalName)
        DoDTestSupport.deleteImageRows(context, fallbackName)
        VaultSession.begin("1234", namespace = namespace)
        repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
    }

    @After
    fun cleanUp() {
        DoDTestSupport.deleteImageRows(context, originalName)
        DoDTestSupport.deleteImageRows(context, fallbackName)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun restoreReturnsFileToOriginalRelativePathAndMediaStoreSeesItAgain() =
        runBlocking {
            val original = DoDTestSupport.sampleJpegBytes()
            val stored = hideOne(originalName, sourceRelativePath = relativePath, bytes = original)
            // Hidden: the public original is gone from the OS media index.
            assertThat(DoDTestSupport.imageRowCount(context, originalName)).isEqualTo(0)

            val summary = repo.unhideDetailed(setOf(stored.id))

            // The operation reports full success — nothing failed, nothing lost.
            assertThat(summary.restored).isEqualTo(1)
            assertThat(summary.failed).isEqualTo(0)
            // MediaStore indexes the file again ("watch it return to the gallery")…
            assertThat(DoDTestSupport.imageRowCount(context, originalName)).isEqualTo(1)
            // …at the exact RELATIVE_PATH it was hidden from, byte-for-byte.
            assertThat(DoDTestSupport.imageRelativePath(context, originalName)).isEqualTo(relativePath)
            assertThat(DoDTestSupport.readImageBytes(context, originalName)).isEqualTo(original)
            // The restored item left the vault (index cleared, blob dropped by unhide).
            assertThat(repo.allItems().first()).isEmpty()
        }

    @Test
    fun restoreWithoutOriginalPathLandsInVisibleDownloadsFallback() =
        runBlocking {
            // relativePath = null models a hidden item whose original location is unknown /
            // no longer resolvable — MediaSink's documented fallback trigger. (A same-name
            // collision at the original path is NOT a fallback trigger in-process: MediaStore
            // auto-uniquifies the DISPLAY_NAME at the same RELATIVE_PATH on API 29+.)
            val original = DoDTestSupport.sampleJpegBytes()
            val stored = hideOne(fallbackName, sourceRelativePath = null, bytes = original)
            assertThat(DoDTestSupport.imageRowCount(context, fallbackName)).isEqualTo(0)

            val summary = repo.unhideDetailed(setOf(stored.id))

            // Restore succeeded and never failed silently: the file is back in MediaStore,
            // in the always-visible Downloads fallback (§7), and the summary classifies it
            // as a fallback with the destination the snackbar shows (spec §8 reporting).
            assertThat(summary.restored).isEqualTo(1)
            assertThat(summary.failed).isEqualTo(0)
            assertThat(summary.restoredToFallback).isEqualTo(1)
            assertThat(summary.fallbackDestination).isEqualTo("Downloads")
            assertThat(DoDTestSupport.imageRowCount(context, fallbackName)).isEqualTo(1)
            assertThat(DoDTestSupport.imageRelativePath(context, fallbackName)).isEqualTo("Download/")
            assertThat(DoDTestSupport.readImageBytes(context, fallbackName)).isEqualTo(original)
        }

    /** Plant a public image, hide it through the real repository, and remove the original. */
    private suspend fun hideOne(
        name: String,
        sourceRelativePath: String?,
        bytes: ByteArray,
    ): VaultItem {
        // The fixture always needs a real public source; a test that models an unknown
        // original path simply omits relativePath from the staged item below.
        val sourceUri = DoDTestSupport.insertPublicImage(context, name, relativePath, bytes)
        val staged =
            VaultItem(
                id = "staged",
                category = VaultCategory.PHOTOS,
                originalName = name,
                dateLabel = "Today",
                sortKey = System.currentTimeMillis(),
                sourceUri = sourceUri.toString(),
                mimeType = "image/jpeg",
                relativePath = sourceRelativePath,
            )
        val stored = repo.hide(listOf(staged)).single()
        // The hide screen's delete-request beat: drop the public original (our own row).
        context.contentResolver.delete(sourceUri, null, null)
        return stored
    }
}
