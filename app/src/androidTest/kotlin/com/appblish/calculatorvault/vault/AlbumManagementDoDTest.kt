package com.appblish.calculatorvault.vault

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.UnhideDestination
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
 * W2-E DoD proof for **album management** (APP-279, spec §3 + §7) against the real
 * [EncryptedVaultContentRepository]:
 *
 * 1. **Rename is index-label-only** — blob file names unchanged, no readable folder
 *    created anywhere in browsable storage, the new name absent from the index file's
 *    bytes (encrypted at rest), and the rename survives a repository reload.
 * 2. **Album move is a merge** — members' index entries relocate, the emptied source
 *    label is removed, no blob moves or is decrypted; persists across reload.
 * 3. **Album unhide** — per-file fallback (missing original path → Downloads) with an
 *    honest per-destination summary; a fully-emptied album loses its label, while a
 *    partial failure keeps the album holding exactly the failed photos.
 * 4. **Bin vs Permanent** — Bin keeps every blob encrypted with the album grouping
 *    preserved for a whole-album restore (design F-3, across reload); Permanent securely
 *    wipes blobs + index entries + label.
 *
 * All assertions are in-process file/flow inspection on the CI instrumented matrix — no
 * manual emulator screenshots (board hard rule).
 */
@RunWith(AndroidJUnit4::class)
class AlbumManagementDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_album_mgmt"
    private val stamp = System.nanoTime()

    private fun displayName(i: Int) = "calcvault_dod_album_${stamp}_$i.jpg"

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
        (0 until 8).forEach { DoDTestSupport.deleteImageRows(context, displayName(it)) }
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    private fun unlockedRepo(): EncryptedVaultContentRepository {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        return repo
    }

    private suspend fun hideOne(
        repo: EncryptedVaultContentRepository,
        index: Int,
        withOriginalPath: Boolean = true,
    ): VaultItem {
        val bytes = DoDTestSupport.sampleJpegBytes()
        val uri = DoDTestSupport.insertPublicImage(context, displayName(index), relativePath, bytes)
        val staged =
            VaultItem(
                id = "staged$index",
                category = VaultCategory.PHOTOS,
                originalName = displayName(index),
                dateLabel = "Today",
                sortKey = 1_000L + index,
                sourceUri = uri.toString(),
                mimeType = "image/jpeg",
                // A null original path is the fallback trigger: unhide must land in
                // Downloads and say so (spec §1.4).
                relativePath = if (withOriginalPath) relativePath else null,
            )
        return repo.hide(listOf(staged)).single()
    }

    /** Bare-UUID blob names currently in the vault dir (order-insensitive snapshot). */
    private fun blobNames(): Set<String> {
        val vaultDir = VaultStorage.blobFile(context, "probe").parentFile!!
        return vaultDir
            .listFiles { f -> f.isFile && f.name.matches(DoDTestSupport.UUID_REGEX) }
            .orEmpty()
            .mapTo(mutableSetOf()) { it.name }
    }

    // ---------------------------------------------------------------------------------
    // 1 · Rename: index-label-only, encrypted at rest, persistent
    // ---------------------------------------------------------------------------------

    @Test
    fun renameIsIndexLabelOnlyAndSurvivesReload() =
        runBlocking {
            val repo = unlockedRepo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val stored = listOf(hideOne(repo, 0), hideOne(repo, 1))
            repo.moveToFolder(stored.map { it.id }.toSet(), folder.id)
            val blobsBefore = blobNames()
            assertThat(blobsBefore).hasSize(2)

            repo.renameFolder(folder.id, "HolidaySecret")

            // Label changed in the index…
            val renamed = repo.folders(VaultCategory.PHOTOS).first().first { it.id == folder.id }
            assertThat(renamed.name).isEqualTo("HolidaySecret")
            // …and NOTHING else: no blob renamed, both members untouched.
            assertThat(blobNames()).isEqualTo(blobsBefore)
            assertThat(
                repo
                    .items(VaultCategory.PHOTOS)
                    .first()
                    .map { it.folderId }
                    .distinct(),
            ).containsExactly(folder.id)

            // No readable folder appears anywhere a file manager could browse: neither the
            // vault dir nor the storage root gained a directory named after the album.
            val vaultDir = VaultStorage.blobFile(context, "probe").parentFile!!
            assertThat(vaultDir.listFiles { f -> f.isDirectory && f.name == "HolidaySecret" }.orEmpty()).isEmpty()
            val storageRoot = android.os.Environment.getExternalStorageDirectory()
            assertThat(java.io.File(storageRoot, "HolidaySecret").exists()).isFalse()

            // The index file itself never leaks the label in plaintext (encrypted at rest).
            val indexBytes = String(VaultStorage.indexFile(context).readBytes(), Charsets.ISO_8859_1)
            assertThat(indexBytes).doesNotContain("HolidaySecret")

            // Reload (navigate-away-and-back at the storage seam): the rename persisted.
            VaultSession.begin("1234", namespace = namespace)
            val reloaded = unlockedRepo()
            val after = reloaded.folders(VaultCategory.PHOTOS).first().first { it.id == folder.id }
            assertThat(after.name).isEqualTo("HolidaySecret")
            assertThat(reloaded.items(VaultCategory.PHOTOS).first()).hasSize(2)
        }

    // ---------------------------------------------------------------------------------
    // 2 · Move/merge: index entries relocate, source label removed, blobs untouched
    // ---------------------------------------------------------------------------------

    @Test
    fun albumMergeRelocatesIndexEntriesAndRemovesSourceLabel() =
        runBlocking {
            val repo = unlockedRepo()
            val source = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val target = repo.createFolder(VaultCategory.PHOTOS, "Screenshots")
            val a = hideOne(repo, 0)
            val b = hideOne(repo, 1)
            val c = hideOne(repo, 2)
            repo.moveToFolder(setOf(a.id, b.id), source.id)
            repo.moveToFolder(setOf(c.id), target.id)
            val blobsBefore = blobNames()

            val moved = repo.mergeFolders(setOf(source.id), target.id)

            assertThat(moved).isEqualTo(2)
            // Every member now reads target; the source label is gone from album surfaces.
            val items = repo.items(VaultCategory.PHOTOS).first()
            assertThat(items.map { it.folderId }.distinct()).containsExactly(target.id)
            assertThat(repo.folders(VaultCategory.PHOTOS).first().map { it.id }).doesNotContain(source.id)
            // Merge is index arithmetic only — the blob set is byte-for-byte the same files.
            assertThat(blobNames()).isEqualTo(blobsBefore)
            // Members still decrypt (nothing was re-encrypted or moved).
            assertThat(repo.openDecrypted(a.id)).isNotNull()

            // Persisted: a fresh repository reads the merged layout.
            VaultSession.begin("1234", namespace = namespace)
            val reloaded = unlockedRepo()
            assertThat(
                reloaded
                    .items(VaultCategory.PHOTOS)
                    .first()
                    .map { it.folderId }
                    .distinct(),
            ).containsExactly(target.id)
            assertThat(reloaded.folders(VaultCategory.PHOTOS).first().map { it.id }).doesNotContain(source.id)
        }

    // ---------------------------------------------------------------------------------
    // 3 · Unhide: per-file fallback + honest summary; partial failure keeps the album
    // ---------------------------------------------------------------------------------

    @Test
    fun albumUnhideFallsBackPerFileAndRemovesEmptiedLabel() =
        runBlocking {
            val repo = unlockedRepo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Mixed")
            val good = hideOne(repo, 3, withOriginalPath = true)
            val lost = hideOne(repo, 4, withOriginalPath = false)
            repo.moveToFolder(setOf(good.id, lost.id), folder.id)

            val result = repo.unhideFolders(setOf(folder.id), UnhideDestination.Original)

            // One back to its original path, one fallen back — and the summary says so.
            assertThat(result.requested).isEqualTo(1)
            assertThat(result.fellBack).isEqualTo(1)
            assertThat(result.failed).isEqualTo(0)
            assertThat(result.fallbackDestination).isEqualTo("Downloads")
            // Both photos are really back in the gallery (MediaStore rows exist).
            assertThat(DoDTestSupport.imageRowCount(context, displayName(3))).isEqualTo(1)
            assertThat(DoDTestSupport.imageRowCount(context, displayName(4))).isEqualTo(1)
            assertThat(DoDTestSupport.imageRelativePath(context, displayName(3))).isEqualTo(relativePath)
            // The fully-emptied album lost its label; nothing remains in the vault.
            assertThat(repo.folders(VaultCategory.PHOTOS).first().map { it.id }).doesNotContain(folder.id)
            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
        }

    @Test
    fun albumUnhidePartialFailureKeepsAlbumHoldingExactlyTheFailedPhotos() =
        runBlocking {
            val repo = unlockedRepo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Flaky")
            val ok = hideOne(repo, 5)
            val broken = hideOne(repo, 6)
            repo.moveToFolder(setOf(ok.id, broken.id), folder.id)
            // Sabotage one blob so its unhide fails (missing ciphertext — nothing to decrypt).
            VaultStorage.blobFile(context, broken.encryptedPath!!).delete()

            val result = repo.unhideFolders(setOf(folder.id), UnhideDestination.Original)

            assertThat(result.unhidden).isEqualTo(1)
            assertThat(result.failed).isEqualTo(1)
            // The album SURVIVES, holding exactly the failed photo (design §6) — succeeded
            // items gone, failed item present, label still on the grid.
            assertThat(repo.folders(VaultCategory.PHOTOS).first().map { it.id }).contains(folder.id)
            val held = repo.items(VaultCategory.PHOTOS).first()
            assertThat(held.map { it.id }).containsExactly(broken.id)
            assertThat(held.single().folderId).isEqualTo(folder.id)
        }

    // ---------------------------------------------------------------------------------
    // 4 · Delete: Bin keeps grouping for a whole-album restore; Permanent secure-wipes
    // ---------------------------------------------------------------------------------

    @Test
    fun albumBinKeepsGroupingAcrossReloadAndPermanentSecurelyWipes() =
        runBlocking {
            val repo = unlockedRepo()
            val binned = repo.createFolder(VaultCategory.PHOTOS, "ToBin")
            val wiped = repo.createFolder(VaultCategory.PHOTOS, "ToWipe")
            val b1 = hideOne(repo, 0)
            val b2 = hideOne(repo, 1)
            val w1 = hideOne(repo, 2)
            repo.moveToFolder(setOf(b1.id, b2.id), binned.id)
            repo.moveToFolder(setOf(w1.id), wiped.id)

            // --- Bin path -----------------------------------------------------------------
            val binnedCount = repo.moveFoldersToRecycleBin(setOf(binned.id))
            assertThat(binnedCount).isEqualTo(2)
            // Album gone from every album surface; contents in the bin, still encrypted.
            assertThat(repo.folders(VaultCategory.PHOTOS).first().map { it.id }).doesNotContain(binned.id)
            listOf(b1, b2).forEach { item ->
                val blob = VaultStorage.blobFile(context, item.encryptedPath!!)
                assertThat(blob.exists()).isTrue()
                assertThat(blob.readBytes().take(3).toByteArray()).isNotEqualTo(DoDTestSupport.JPEG_MAGIC)
                assertThat(DoDTestSupport.imageRowCount(context, item.originalName)).isEqualTo(0)
            }

            // Reload, then restore: the album comes back WHOLE — same id, same name, both
            // photos inside (design F-3, the Bin kept the grouping).
            VaultSession.begin("1234", namespace = namespace)
            val reloaded = unlockedRepo()
            assertThat(
                reloaded
                    .recycleBin()
                    .first()
                    .map { it.item.folderId }
                    .distinct()
            ).containsExactly(binned.id)
            val restored = reloaded.restore(setOf(b1.id, b2.id))
            assertThat(restored).isEqualTo(2)
            val back = reloaded.folders(VaultCategory.PHOTOS).first().first { it.id == binned.id }
            assertThat(back.name).isEqualTo("ToBin")
            assertThat(
                reloaded
                    .items(VaultCategory.PHOTOS)
                    .first()
                    .filter { it.folderId == binned.id }
                    .map { it.id },
            ).containsExactly(b1.id, b2.id)

            // --- Permanent path -------------------------------------------------------------
            val wipeBlob = VaultStorage.blobFile(context, w1.encryptedPath!!)
            assertThat(wipeBlob.exists()).isTrue()
            val erased = reloaded.permanentlyDeleteFolders(setOf(wiped.id))
            assertThat(erased).isEqualTo(1)
            // Blob destroyed (secure wipe → unlink), label gone, nothing routed via the bin.
            assertThat(wipeBlob.exists()).isFalse()
            assertThat(reloaded.folders(VaultCategory.PHOTOS).first().map { it.id }).doesNotContain(wiped.id)
            assertThat(reloaded.recycleBin().first().map { it.item.id }).doesNotContain(w1.id)
        }
}
