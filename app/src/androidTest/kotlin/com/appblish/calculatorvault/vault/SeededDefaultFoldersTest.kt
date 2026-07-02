package com.appblish.calculatorvault.vault

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof of APP-206: a fresh vault seeds the predefined default folders (xlock /
 * Figma parity) into its encrypted index on first init, the seed happens **once** (re-opening
 * does not duplicate), and each vault namespace seeds independently so a decoy's folders never
 * leak into the real vault and vice-versa.
 */
@RunWith(AndroidJUnit4::class)
class SeededDefaultFoldersTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val realPin = "1234"
    private val decoyPin = "5678"
    private val decoyNamespace = "decoy_1"

    @Before
    fun grantAllFilesAccessAndClean() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
        )
        repeat(40) { if (StoragePermissions.hasAllFilesAccess(context)) return@repeat else Thread.sleep(50) }
        assertThat(StoragePermissions.hasAllFilesAccess(context)).isTrue()
        File(Environment.getExternalStorageDirectory(), VaultStorage.DIR_NAME).deleteRecursively()
    }

    @Test
    fun freshVaultSeedsDefaultFoldersOnceAndDecoysStayIsolated() =
        runBlocking {
            // --- Real vault: first unlock seeds the defaults --------------------------------
            VaultSession.begin(realPin, namespace = "")
            val real = EncryptedVaultContentRepository(context)
            real.unlock()
            awaitUnlock(real)

            // Seeded folders load into the index a beat after the key is derived; wait for them.
            val realPhotoFolders = awaitPhotoFolders(real, expected = 2)
            assertThat(realPhotoFolders).containsExactly("Camera", "Screenshots")
            assertThat(real.folderCounts().first()[VaultCategory.FILES]).isEqualTo(1)
            // Contacts stay folderless (home tile shows a plain count).
            assertThat(real.folderCounts().first()[VaultCategory.CONTACTS]).isEqualTo(0)

            // A user-created folder in the real vault, to prove isolation below.
            real.createFolder(VaultCategory.PHOTOS, "RealSecrets")

            // --- Re-open the real vault: seed must NOT run again ----------------------------
            VaultSession.begin(realPin, namespace = "")
            val realReopened = EncryptedVaultContentRepository(context)
            realReopened.unlock()
            awaitUnlock(realReopened)
            // Camera + Screenshots (seed) + RealSecrets (user) = 3, no duplicated seed folders.
            assertThat(awaitPhotoFolders(realReopened, expected = 3))
                .containsExactly("Camera", "Screenshots", "RealSecrets")

            // --- Decoy vault: its own independent seed, no real-vault leakage ---------------
            VaultSession.begin(decoyPin, namespace = decoyNamespace)
            val decoy = EncryptedVaultContentRepository(context)
            decoy.unlock()
            awaitUnlock(decoy)

            val decoyPhotoFolders = awaitPhotoFolders(decoy, expected = 2)
            // Seeded defaults present…
            assertThat(decoyPhotoFolders).containsExactly("Camera", "Screenshots")
            // …but the real vault's user folder is absent — spaces are isolated by directory.
            assertThat(decoyPhotoFolders).doesNotContain("RealSecrets")

            // Decoy index lives in its own sub-directory; real vault never saw the decoy pin.
            assertThat(VaultStorage.indexFile(context, decoyNamespace).path).contains("/$decoyNamespace/")

            VaultSession.begin(realPin, namespace = "")
        }

    private fun awaitUnlock(repo: EncryptedVaultContentRepository) {
        repeat(200) { if (repo.isUnlocked()) return else Thread.sleep(100) }
        assertThat(repo.isUnlocked()).isTrue()
    }

    /**
     * Poll the Photos folder flow until it reaches [expected] entries. [isUnlocked] flips true
     * the moment the data key is derived, but the index (and thus the folder state) is loaded a
     * beat later; production observes the StateFlow, so this only bridges the test's imperative
     * read.
     */
    private fun awaitPhotoFolders(
        repo: EncryptedVaultContentRepository,
        expected: Int,
    ): List<String> =
        runBlocking {
            repeat(150) {
                val names = repo.folders(VaultCategory.PHOTOS).first().map { it.name }
                if (names.size >= expected) return@runBlocking names
                Thread.sleep(100)
            }
            repo.folders(VaultCategory.PHOTOS).first().map { it.name }
        }
}
