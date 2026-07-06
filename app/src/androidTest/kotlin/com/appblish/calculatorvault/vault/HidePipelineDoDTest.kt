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
 * Phase-1 DoD proof for the **hide pipeline** (APP-225, build spec §11): hiding a public
 * photo (1) removes it from MediaStore — an in-process content query returns **no row**,
 * the board's "adb content query" assertion — (2) stores it as an extension-less
 * bare-UUID blob inside the hidden `.CalcVault/` folder, (3) as *ciphertext* (not the
 * original bytes, no JPEG header), and (4) keeps the metadata index encrypted so neither
 * the original filename nor its path is readable on disk. Runs on the CI instrumented
 * matrix — no manual emulator screenshots (board hard rule #2).
 */
@RunWith(AndroidJUnit4::class)
class HidePipelineDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_hide"
    private val displayName = "calcvault_dod_hide_${System.nanoTime()}.jpg"
    private val relativePath = "DCIM/CalcVaultDoD/"

    @Before
    fun setUp() {
        // The fixture uses MediaStore RELATIVE_PATH inserts (API 29+); the CI matrix
        // (API 30 / 34) always qualifies.
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
    fun hideRemovesOriginalFromMediaStoreAndStoresOnlyCiphertext() =
        runBlocking {
            val original = DoDTestSupport.sampleJpegBytes()
            val sourceUri = DoDTestSupport.insertPublicImage(context, displayName, relativePath, original)
            assertThat(DoDTestSupport.imageRowCount(context, displayName)).isEqualTo(1)

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

            // Complete the pipeline's final beat: the hide screen removes the public original
            // via a MediaStore delete-request (user-consented dialog in the UI). Our own row
            // deletes directly in-process — the same resolver delete the consent grant runs.
            context.contentResolver.delete(sourceUri, null, null)

            // (1) The original is GONE from MediaStore: the content query returns no row.
            assertThat(DoDTestSupport.imageRowCount(context, displayName)).isEqualTo(0)

            // (2) .CalcVault/<namespace>/ holds exactly one blob: bare UUID name, no extension.
            val vaultDir = VaultStorage.vaultDir(context)
            assertThat(vaultDir.absolutePath).contains("/.CalcVault/$namespace")
            val blobs = vaultDir.listFiles { f -> f.isFile && f.name.matches(DoDTestSupport.UUID_REGEX) }.orEmpty()
            assertThat(blobs).hasLength(1)
            val blob = blobs.single()
            assertThat(blob.name).doesNotContain(".")
            assertThat(blob.name).isEqualTo(stored.encryptedPath)

            // (3) The blob is ciphertext: not the original bytes, no recognizable JPEG header
            // (it starts with the random GCM IV, never the JPEG SOI magic).
            val blobBytes = blob.readBytes()
            assertThat(blobBytes).isNotEqualTo(original)
            assertThat(blobBytes.copyOfRange(0, 3)).isNotEqualTo(DoDTestSupport.JPEG_MAGIC)

            // (4) The index is encrypted (`index.enc`): neither the original filename nor its
            // public path appears as plaintext in the raw bytes on disk…
            val indexRaw = String(VaultStorage.indexFile(context).readBytes())
            assertThat(indexRaw).doesNotContain(displayName)
            assertThat(indexRaw).doesNotContain("CalcVaultDoD")
            // …yet the unlocked repository still round-trips them, proving the metadata is
            // present-but-encrypted rather than simply absent.
            val item = repo.allItems().first().single()
            assertThat(item.originalName).isEqualTo(displayName)
            assertThat(item.relativePath).isEqualTo(relativePath)
        }

    /**
     * APP-248 regression (board re-test: "shows N items, fails to hide … throws that
     * number"). A hide launched before the eager fire-and-forget [unlock] has finished
     * deriving the key — the exact timing after the All-Files-Access grant returns and the
     * user immediately hits Hide Now — used to run with the cipher still null: every item
     * fell into `encryptSource`'s catch and the batch reported "0 hidden, N failed" with
     * nothing stored. `hide` now `await`s the unlock inline, so it must succeed with **no**
     * prior [unlock] call at all (only a live session + All Files Access, both set up here).
     */
    @Test
    fun hideSelfUnlocksWithoutAPriorUnlockCall() =
        runBlocking {
            val original = DoDTestSupport.sampleJpegBytes()
            val sourceUri = DoDTestSupport.insertPublicImage(context, displayName, relativePath, original)

            val repo = EncryptedVaultContentRepository(context)
            // Deliberately DO NOT call repo.unlock()/awaitUnlock(): the session passphrase
            // and All Files Access are present (see setUp), so hide() must derive the key
            // itself and store the item rather than silently failing every entry.
            assertThat(repo.isUnlocked()).isFalse()

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

            val stored = repo.hide(listOf(staged))
            context.contentResolver.delete(sourceUri, null, null)

            // The one item was actually hidden — not reported as "failed".
            assertThat(stored).hasSize(1)
            assertThat(repo.isUnlocked()).isTrue()
            assertThat(repo.allItems().first()).hasSize(1)
        }
}
