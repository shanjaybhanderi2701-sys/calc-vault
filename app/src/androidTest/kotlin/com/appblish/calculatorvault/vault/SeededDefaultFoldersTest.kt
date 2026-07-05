package com.appblish.calculatorvault.vault

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof of the Phase-1 seed contract (build spec §4, APP-225, board-ruled on
 * APP-220): a fresh vault seeds exactly **one empty "Download" folder** into each Phase-1
 * category (Photos / Videos / Audios) with the stable id `seed_<category>_download`,
 * FILES and CONTACTS seed **nothing**, the seed happens **once** (re-opening does not
 * duplicate or resurrect), and each vault namespace seeds independently so a decoy's
 * folders never leak into the real vault and vice-versa.
 */
@RunWith(AndroidJUnit4::class)
class SeededDefaultFoldersTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val realPin = "1234"
    private val decoyPin = "5678"
    private val decoyNamespace = "decoy_1"

    @Before
    fun grantAllFilesAccessAndClean() {
        DoDTestSupport.grantAllFilesAccess(context)
        // This test exercises the ROOT namespace's first-init seed, so it needs a truly
        // fresh .CalcVault/ tree (safe: CI runs on a dedicated, disposable emulator).
        File(Environment.getExternalStorageDirectory(), VaultStorage.DIR_NAME).deleteRecursively()
    }

    @After
    fun clearSession() {
        VaultSession.clear()
    }

    @Test
    fun freshVaultSeedsOneDownloadFolderPerPhase1CategoryOnceAndDecoysStayIsolated() =
        runBlocking {
            // --- Real vault: first unlock seeds the Phase-1 defaults ------------------------
            VaultSession.begin(realPin, namespace = "")
            val real = EncryptedVaultContentRepository(context)
            real.unlock()
            DoDTestSupport.awaitUnlock(real)

            // Each Phase-1 category (Photos / Videos / Audios) gets exactly one empty
            // "Download" folder with the stable derived id.
            for (category in VaultCategory.PHASE1) {
                val seeded = awaitFolders(real, category, expected = 1)
                assertThat(seeded.map { it.name }).containsExactly("Download")
                val download = seeded.single()
                assertThat(download.id).isEqualTo("seed_${category.name.lowercase()}_download")
                assertThat(download.itemCount).isEqualTo(0)
            }
            // FILES and CONTACTS are out of Phase-1 scope: no folders seeded at all.
            val folderCounts = real.folderCounts().first()
            assertThat(folderCounts[VaultCategory.FILES]).isEqualTo(0)
            assertThat(folderCounts[VaultCategory.CONTACTS]).isEqualTo(0)

            // A user-created folder in the real vault, to prove isolation below.
            real.createFolder(VaultCategory.PHOTOS, "RealSecrets")

            // --- Re-open the real vault: seed must NOT run again ----------------------------
            VaultSession.begin(realPin, namespace = "")
            val realReopened = EncryptedVaultContentRepository(context)
            realReopened.unlock()
            DoDTestSupport.awaitUnlock(realReopened)
            // Download (seed) + RealSecrets (user) = 2 — no duplicated seed folder.
            assertThat(awaitFolders(realReopened, VaultCategory.PHOTOS, expected = 2).map { it.name })
                .containsExactly("Download", "RealSecrets")

            // --- Decoy vault: its own independent seed, no real-vault leakage ---------------
            VaultSession.begin(decoyPin, namespace = decoyNamespace)
            val decoy = EncryptedVaultContentRepository(context)
            decoy.unlock()
            DoDTestSupport.awaitUnlock(decoy)

            val decoyPhotoFolders = awaitFolders(decoy, VaultCategory.PHOTOS, expected = 1).map { it.name }
            // Seeded default present…
            assertThat(decoyPhotoFolders).containsExactly("Download")
            // …but the real vault's user folder is absent — spaces are isolated by directory.
            assertThat(decoyPhotoFolders).doesNotContain("RealSecrets")

            // Decoy index lives in its own sub-directory; real vault never saw the decoy pin.
            assertThat(VaultStorage.indexFile(context, decoyNamespace).path).contains("/$decoyNamespace/")

            VaultSession.begin(realPin, namespace = "")
        }

    /**
     * Poll [category]'s folder flow until it reaches [expected] entries. `isUnlocked` flips
     * true the moment the data key is derived, but the index (and thus the folder state) is
     * loaded a beat later; production observes the StateFlow, so this only bridges the test's
     * imperative read.
     */
    private fun awaitFolders(
        repo: EncryptedVaultContentRepository,
        category: VaultCategory,
        expected: Int,
    ): List<VaultFolder> =
        runBlocking {
            repeat(150) {
                val folders = repo.folders(category).first()
                if (folders.size >= expected) return@runBlocking folders
                Thread.sleep(100)
            }
            repo.folders(category).first()
        }
}
