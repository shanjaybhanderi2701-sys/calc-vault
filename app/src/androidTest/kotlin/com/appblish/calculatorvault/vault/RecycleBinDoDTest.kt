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
 * Phase-1 DoD proof for the **recycle bin** (APP-225, build spec §9/§11): deleting a
 * vault item to the bin keeps its blob *encrypted inside the vault* and publishes nothing
 * back to MediaStore; restore-from-bin returns it to its category (still vault-only);
 * only delete-forever destroys the blob file. All assertions are in-process content
 * queries + direct file inspection on the CI instrumented matrix — no manual emulator
 * screenshots (board hard rule #2).
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_bin"
    private val displayName = "calcvault_dod_bin_${System.nanoTime()}.jpg"
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
        DoDTestSupport.deleteImageRows(context, displayName)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun binKeepsBlobEncryptedInVaultRestoreReturnsItAndDeleteForeverDestroysIt() =
        runBlocking {
            val original = DoDTestSupport.sampleJpegBytes()
            val sourceUri = DoDTestSupport.insertPublicImage(context, displayName, relativePath, original)

            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            val staged =
                VaultItem(
                    id = "staged",
                    category = VaultCategory.PHOTOS,
                    originalName = displayName,
                    dateLabel = "Today",
                    sortKey = System.currentTimeMillis(),
                    sourceUri = sourceUri.toString(),
                    mimeType = "image/jpeg",
                    relativePath = relativePath,
                )
            val stored = repo.hide(listOf(staged)).single()
            context.contentResolver.delete(sourceUri, null, null)
            val blob = VaultStorage.blobFile(context, stored.encryptedPath!!)
            assertThat(blob.exists()).isTrue()

            // --- Delete-to-bin: blob stays encrypted in the vault, out of MediaStore --------
            repo.moveToRecycleBin(setOf(stored.id))
            assertThat(repo.recycleBin().first().map { it.item.id }).containsExactly(stored.id)
            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(blob.exists()).isTrue()
            // Still ciphertext on disk (no JPEG header, not the original bytes)…
            val blobBytes = blob.readBytes()
            assertThat(blobBytes).isNotEqualTo(original)
            assertThat(blobBytes.copyOfRange(0, 3)).isNotEqualTo(DoDTestSupport.JPEG_MAGIC)
            // …and nothing leaked back into the OS media index.
            assertThat(DoDTestSupport.imageRowCount(context, displayName)).isEqualTo(0)

            // --- Restore-from-bin: back to its category, still vault-only -------------------
            repo.restore(setOf(stored.id))
            assertThat(repo.recycleBin().first()).isEmpty()
            assertThat(repo.items(VaultCategory.PHOTOS).first().map { it.id }).containsExactly(stored.id)
            assertThat(blob.exists()).isTrue()
            assertThat(DoDTestSupport.imageRowCount(context, displayName)).isEqualTo(0)
            // The restored entry still decrypts to the original bytes (nothing was lost).
            assertThat(repo.openDecrypted(stored.id)).isEqualTo(original)

            // --- Delete-forever: the blob file is destroyed for good ------------------------
            repo.moveToRecycleBin(setOf(stored.id))
            repo.deleteForever(setOf(stored.id))
            assertThat(repo.recycleBin().first()).isEmpty()
            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(blob.exists()).isFalse()
        }
}
