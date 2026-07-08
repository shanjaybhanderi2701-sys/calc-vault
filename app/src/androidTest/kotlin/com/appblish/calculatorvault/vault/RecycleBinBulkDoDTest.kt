package com.appblish.calculatorvault.vault

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/**
 * W1-E4 DoD proof for the **Recycle Bin** (APP-257, spec §1/§7) against the real
 * [EncryptedVaultContentRepository], covering the bulk + navigate-away-and-back scenarios
 * the single-item [RecycleBinDoDTest] does not:
 *
 * 1. A **bulk** delete→Bin keeps every blob AES-encrypted inside the vault (ciphertext on
 *    disk, nothing republished to MediaStore).
 * 2. The bin **survives a repository reload** — the navigate-away-and-back / relaunch
 *    equivalent at the storage seam: a fresh repository instance reads the same entries
 *    from the encrypted index.
 * 3. **Restore round-trip** returns the index entry to its album (folder id intact) with
 *    the blob still decrypting to the original bytes.
 * 4. **Bulk summaries** are honest ("X done, Y failed"): an id that no longer resolves
 *    counts as failed, never silently dropped.
 * 5. **Delete Permanently** from the bin destroys the blob files (secure wipe → unlink).
 *
 * All assertions are in-process file/flow inspection on the CI instrumented matrix — no
 * manual emulator screenshots (board hard rule #2).
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinBulkDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_bin_bulk"
    private val stamp = System.nanoTime()

    private fun displayName(i: Int) = "calcvault_dod_binbulk_${stamp}_$i.jpg"

    private val relativePath = "DCIM/CalcVaultDoD/"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        (0 until COUNT).forEach { DoDTestSupport.deleteImageRows(context, displayName(it)) }
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun bulkBinRetainsEncryptedBlobsAcrossReloadThenRestoresAndSecurelyWipes() =
        runBlocking {
            val original = DoDTestSupport.sampleJpegBytes()
            val staged =
                (0 until COUNT).map { i ->
                    val uri = DoDTestSupport.insertPublicImage(context, displayName(i), relativePath, original)
                    VaultItem(
                        id = "staged$i",
                        category = VaultCategory.PHOTOS,
                        originalName = displayName(i),
                        dateLabel = "Today",
                        sortKey = 1_000L + i,
                        sourceUri = uri.toString(),
                        mimeType = "image/jpeg",
                        relativePath = relativePath,
                    )
                }

            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            val stored = repo.hide(staged)
            assertThat(stored).hasSize(COUNT)
            // Park one item in an album so the restore round-trip proves folder retention.
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Trip")
            repo.moveToFolder(setOf(stored[0].id), folder.id)

            // --- 1) Bulk delete → Bin: every blob stays, encrypted, unpublished ------------
            repo.moveToRecycleBin(stored.map { it.id }.toSet())
            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(repo.recycleBin().first()).hasSize(COUNT)
            stored.forEach { item ->
                val blob = VaultStorage.blobFile(context, item.encryptedPath!!)
                assertThat(blob.exists()).isTrue()
                val head = blob.readBytes().take(3).toByteArray()
                // Ciphertext, not a readable JPEG copy (spec §1: encrypted at rest in the Bin).
                assertThat(head).isNotEqualTo(DoDTestSupport.JPEG_MAGIC)
            }
            (0 until COUNT).forEach { i ->
                assertThat(DoDTestSupport.imageRowCount(context, displayName(i))).isEqualTo(0)
            }

            // --- 2) Navigate-away-and-back / relaunch: a fresh repository reads the bin ----
            VaultSession.begin("1234", namespace = namespace)
            val reloaded = EncryptedVaultContentRepository(context)
            reloaded.unlock()
            DoDTestSupport.awaitUnlock(reloaded)
            val binAfterReload = reloaded.recycleBin().first()
            assertThat(binAfterReload.map { it.item.id }).containsExactlyElementsIn(stored.map { it.id })

            // --- 3+4) Bulk restore round-trip with honest accounting ------------------------
            val restoredCount = reloaded.restore(setOf(stored[0].id, stored[1].id, "no-such-id"))
            // 2 of the 3 requested ids resolved — the count gap is the "1 failed" report.
            assertThat(restoredCount).isEqualTo(2)
            val restoredItems = reloaded.items(VaultCategory.PHOTOS).first()
            assertThat(restoredItems.map { it.id }).containsExactly(stored[0].id, stored[1].id)
            // Back to its album, not the root — the index entry came back intact.
            assertThat(restoredItems.first { it.id == stored[0].id }.folderId).isEqualTo(folder.id)
            // The blob still decrypts to the original bytes (restore never touched it).
            assertThat(reloaded.openDecrypted(stored[0].id)).isEqualTo(original)
            // The un-restored entry is still in the bin.
            assertThat(reloaded.recycleBin().first().map { it.item.id }).containsExactly(stored[2].id)

            // --- 5) Delete Permanently: secure wipe destroys the blob, honest count --------
            val wipeBlob = VaultStorage.blobFile(context, stored[2].encryptedPath!!)
            assertThat(wipeBlob.exists()).isTrue()
            val deletedCount = reloaded.deleteForever(setOf(stored[2].id, "no-such-id"))
            assertThat(deletedCount).isEqualTo(1)
            assertThat(wipeBlob.exists()).isFalse()
            assertThat(reloaded.recycleBin().first()).isEmpty()
            // Restored items are untouched by the wipe.
            assertThat(reloaded.items(VaultCategory.PHOTOS).first()).hasSize(2)
        }

    private companion object {
        const val COUNT = 3
    }
}
