package com.appblish.calculatorvault.vault

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
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
     * The migration bug fixed for APP-225: `.CalcVault/` survives uninstall by design, so a
     * device that ever ran an older build already has an `index.enc` — and the old
     * first-run-only seed never fired for it. This fabricates exactly that artifact (a valid
     * key file + an index written with the production crypto stack whose folders are the
     * legacy Camera-style set, plus a FILES item), then unlocks and asserts the
     * category-scoped top-up: empty Phase-1 categories gain "Download", a populated one is
     * left alone (no Download resurrected next to "Camera"), non-Phase-1 content loads
     * untouched, and the top-up is persisted across a relock/unlock round-trip.
     */
    @Test
    fun staleIndexFromOlderBuildGetsMissingDownloadFoldersToppedUpOnUnlock() =
        // <Unit> because JUnit rejects the whole class if a @Test isn't void — the trailing
        // assertThat(...) chain would otherwise become the inferred return value.
        runBlocking<Unit> {
            val namespace = "stale_migration"
            VaultSession.begin(realPin, namespace = namespace)

            // Fabricate the older build's leftovers BEFORE the repository ever runs: key file
            // via VaultKeyFile (the repo will unwrap the same key from the same PIN), index
            // via VaultCrypto — the repo's own persistence format and cipher.
            val dataKey = VaultKeyFile(VaultStorage.keyFile(context, namespace)).unlockOrCreate(realPin)
            val staleIndex =
                JSONObject().apply {
                    put(
                        "items",
                        JSONArray().put(
                            JSONObject().apply {
                                put("id", "legacy-file-1")
                                put("category", VaultCategory.FILES.name)
                                put("originalName", "tax-2025.pdf")
                                put("dateLabel", "12 Jun 2026")
                                put("sortKey", 1L)
                            },
                        ),
                    )
                    put(
                        "folders",
                        JSONArray().put(
                            JSONObject().apply {
                                put("id", "seed_photos_camera")
                                put("category", VaultCategory.PHOTOS.name)
                                put("name", "Camera")
                            },
                        ),
                    )
                    put("bin", JSONArray())
                }
            val encrypted = ByteArrayOutputStream()
            staleIndex.toString().toByteArray(Charsets.UTF_8).inputStream().use { source ->
                VaultCrypto(dataKey).encrypt(source, encrypted)
            }
            VaultStorage.indexFile(context, namespace).writeBytes(encrypted.toByteArray())

            // First unlock of the new build against the stale index.
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            // Photos already holds a folder → left exactly as found, NO Download added.
            assertThat(awaitFolders(repo, VaultCategory.PHOTOS, expected = 1).map { it.name })
                .containsExactly("Camera")
            // Videos and Audios had ZERO folders → each topped up with the seeded default.
            for (category in listOf(VaultCategory.VIDEOS, VaultCategory.AUDIOS)) {
                val seeded = awaitFolders(repo, category, expected = 1)
                assertThat(seeded.map { it.name }).containsExactly("Download")
                assertThat(seeded.single().id).isEqualTo("seed_${category.name.lowercase()}_download")
            }
            // The non-Phase-1 (FILES) item from the old build loads untouched.
            assertThat(repo.allItems().first().map { it.id }).containsExactly("legacy-file-1")

            // Relock/unlock: the top-up was persisted, and it does not run again (still one
            // folder per category — nothing duplicated, "Camera" still un-Downloaded).
            VaultSession.begin(realPin, namespace = namespace)
            val reopened = EncryptedVaultContentRepository(context)
            reopened.unlock()
            DoDTestSupport.awaitUnlock(reopened)
            assertThat(awaitFolders(reopened, VaultCategory.PHOTOS, expected = 1).map { it.name })
                .containsExactly("Camera")
            assertThat(awaitFolders(reopened, VaultCategory.VIDEOS, expected = 1).map { it.name })
                .containsExactly("Download")
            assertThat(awaitFolders(reopened, VaultCategory.AUDIOS, expected = 1).map { it.name })
                .containsExactly("Download")
            assertThat(reopened.allItems().first().map { it.id }).containsExactly("legacy-file-1")
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
