package com.appblish.calculatorvault.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.model.GridSort
import com.appblish.calculatorvault.vault.model.SortDirection
import com.appblish.calculatorvault.vault.model.SortKey
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * W3-E DoD (spec §7 / design W3-D §4–§9) at the encrypted device-repository layer — the
 * persistence half the Compose tests can't prove:
 *
 * 1. **Pin** persists in the encrypted index across a full repository reload (process
 *    death equivalent) and a Bin round-trip returns the album unpinned (G-2).
 * 2. **Cover** pointer persists across reload; it clears when the cover photo leaves the
 *    album and a Bin restore never re-promotes it (G-5); rendering the cover after the
 *    change reads the cached thumbnail pipeline with **zero full-size decrypts**.
 * 3. **Sort** — vault-wide photo + album choices and the per-album override persist
 *    across reload (spec §4.1 "persisted choice", surviving process death).
 * 4. **Rotate** — the net orientation persists across reload; the item's stored encrypted
 *    thumbnail is re-derived rotated (dimensions swap) with **zero full-size decrypts**
 *    and no other item's thumbnail touched (single-item invalidation, §9); a
 *    navigate-away-and-back render serves the rotated tile from cache.
 * 5. **Encrypted at rest** at every step: neither the index nor any stored thumb ever
 *    starts with plaintext JSON or JPEG magic.
 */
@RunWith(AndroidJUnit4::class)
class OrganizationDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_orgpolish"
    private val relativePath = "DCIM/CalcVaultOrgDoD/"
    private val displayNames = mutableListOf<String>()

    /** Counts full-blob decrypts so the cached-thumbnail hard rule is mechanical. */
    private class CountingRepo(
        val delegate: EncryptedVaultContentRepository,
    ) : VaultContentRepository by delegate {
        val fullDecrypts = AtomicInteger(0)

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
    }

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
        VaultThumbnailPipeline.clear()
    }

    @After
    fun cleanUp() {
        displayNames.forEach { DoDTestSupport.deleteImageRows(context, it) }
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
        VaultThumbnailPipeline.clear()
    }

    private fun newRepo(): CountingRepo {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        return CountingRepo(repo)
    }

    /** A landscape (64×32) JPEG so a 90° thumbnail rotation is observable in the bounds. */
    private fun landscapeJpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(200, 40, 90))
        return ByteArrayOutputStream()
            .also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
    }

    private fun stagedImage(
        index: Int,
        bytes: ByteArray = DoDTestSupport.sampleJpegBytes(),
    ): VaultItem {
        val name = "calcvault_org_dod_${System.nanoTime()}_$index.jpg"
        displayNames += name
        val uri = DoDTestSupport.insertPublicImage(context, name, relativePath, bytes)
        return VaultItem(
            id = "staged$index",
            category = VaultCategory.PHOTOS,
            originalName = name,
            dateLabel = "Today",
            sortKey = System.currentTimeMillis() + index,
            sourceUri = uri.toString(),
            mimeType = "image/jpeg",
            relativePath = relativePath,
        )
    }

    private fun assertNotPlaintext(file: File) {
        assertThat(file.exists()).isTrue()
        val head = ByteArray(3)
        val read = file.inputStream().use { it.read(head) }
        assertThat(read).isEqualTo(3)
        // Neither JPEG magic (a plaintext preview) nor '{' (plaintext JSON index).
        assertThat(head.contentEquals(DoDTestSupport.JPEG_MAGIC)).isFalse()
        assertThat(head[0]).isNotEqualTo('{'.code.toByte())
    }

    // ---------------------------------------------------------------------------------
    // 1 · Pin: persisted bit, ordering input, Bin round-trip returns unpinned
    // ---------------------------------------------------------------------------------

    @Test
    fun pinPersistsAcrossReloadAndBinRestoreReturnsUnpinned() =
        runBlocking {
            val repo = newRepo()
            repo.createFolder(VaultCategory.PHOTOS, "Alpha")
            val zulu = repo.createFolder(VaultCategory.PHOTOS, "Zulu")
            val item = repo.hide(listOf(stagedImage(1))).single()
            repo.moveToFolder(setOf(item.id), zulu.id)

            repo.setFolderPinned(zulu.id, true)

            // Reload = new repository over the same encrypted index (process death).
            repo.delegate.lock()
            val reloaded = newRepo()
            val folders = reloaded.folders(VaultCategory.PHOTOS).first()
            assertThat(folders.first { it.id == zulu.id }.pinned).isTrue()
            assertThat(folders.first { it.name == "Alpha" }.pinned).isFalse()

            // Two-cluster ordering: pinned Zulu leads despite Name·Ascending (G-1).
            // (The seeded "Download" default album participates as a normal unpinned tile.)
            val tiles =
                folders.map {
                    CategoryFolderTile(id = it.id, name = it.name, itemCount = 0, pinned = it.pinned)
                }
            assertThat(orderAlbumTiles(tiles, GridSort.ALBUM_DEFAULT).first().name).isEqualTo("Zulu")

            // Bin round-trip drops the pin (G-2).
            reloaded.moveFoldersToRecycleBin(setOf(zulu.id))
            reloaded.restore(setOf(item.id))
            assertThat(
                reloaded
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == zulu.id }
                    .pinned,
            ).isFalse()

            assertNotPlaintext(VaultStorage.indexFile(context))
        }

    // ---------------------------------------------------------------------------------
    // 2 · Cover: persisted pointer, G-5 fallback + no re-promotion, cache-only render
    // ---------------------------------------------------------------------------------

    @Test
    fun coverPersistsFallsBackAndRendersFromCacheWithoutFullDecrypts() =
        runBlocking {
            val repo = newRepo()
            val album = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val stored = repo.hide(listOf(stagedImage(1), stagedImage(2)))
            repo.moveToFolder(stored.mapTo(mutableSetOf()) { it.id }, album.id)
            val older = stored.minByOrNull { it.sortKey }!!

            repo.setFolderCover(album.id, older.id)

            // Persisted: a fresh repository reads the pointer back from the index.
            repo.delegate.lock()
            VaultThumbnailPipeline.clear()
            val reloaded = newRepo()
            val folder = reloaded.folders(VaultCategory.PHOTOS).first().first { it.id == album.id }
            assertThat(folder.coverItemId).isEqualTo(older.id)

            // Rendering the cover tile goes through the cached thumbnail pipeline —
            // hide-time encrypted thumbs, zero full-size decrypts (spec §1.7/§2.7).
            val coverItem = reloaded.allItems().first().first { it.id == older.id }
            val tile = VaultThumbnailPipeline.load(context, coverItem, reloaded)
            assertThat(tile).isNotNull()
            assertThat(reloaded.fullDecrypts.get()).isEqualTo(0)

            // The cover photo leaves for the Bin → pointer drops; restoring the exact
            // former cover does not re-promote it (G-5).
            reloaded.moveToRecycleBin(setOf(older.id))
            assertThat(
                reloaded
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == album.id }
                    .coverItemId,
            ).isNull()
            reloaded.restore(setOf(older.id))
            assertThat(
                reloaded
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == album.id }
                    .coverItemId,
            ).isNull()
        }

    // ---------------------------------------------------------------------------------
    // 3 · Sort: vault-wide choices + per-album override persist across reload
    // ---------------------------------------------------------------------------------

    @Test
    fun sortChoicesAndPerAlbumOverridePersistAcrossReload() =
        runBlocking {
            val repo = newRepo()
            val album = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            assertThat(repo.sortPrefs().first().photoSort).isEqualTo(GridSort.PHOTO_DEFAULT)
            assertThat(repo.sortPrefs().first().albumSort).isEqualTo(GridSort.ALBUM_DEFAULT)

            val sizeAsc = GridSort(SortKey.SIZE, SortDirection.ASCENDING)
            val modifiedDesc = GridSort(SortKey.LAST_MODIFIED, SortDirection.DESCENDING)
            val nameDesc = GridSort(SortKey.NAME, SortDirection.DESCENDING)
            repo.setPhotoSort(sizeAsc)
            repo.setAlbumSort(modifiedDesc)
            repo.setAlbumPhotoSortOverride(album.id, nameDesc)

            repo.delegate.lock()
            val reloaded = newRepo()
            val prefs = reloaded.sortPrefs().first()
            assertThat(prefs.photoSort).isEqualTo(sizeAsc)
            assertThat(prefs.albumSort).isEqualTo(modifiedDesc)
            assertThat(
                reloaded
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .first { it.id == album.id }
                    .photoSortOverride,
            ).isEqualTo(nameDesc)

            assertNotPlaintext(VaultStorage.indexFile(context))
        }

    // ---------------------------------------------------------------------------------
    // 4 · Rotate: persisted orientation + single rotated thumb, cache-only, no blanks
    // ---------------------------------------------------------------------------------

    @Test
    fun rotatePersistsAcrossReloadAndRegeneratesOnlyThatThumbnailRotated() =
        runBlocking {
            val repo = newRepo()
            val stored = repo.hide(listOf(stagedImage(1, landscapeJpegBytes()), stagedImage(2)))
            val target = stored.first { it.originalName.endsWith("_1.jpg") }
            val bystander = stored.first { it.id != target.id }

            suspend fun thumbBounds(itemId: String): Pair<Int, Int> {
                val jpeg = repo.openThumbnail(itemId)
                assertThat(jpeg).isNotNull()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size, opts)
                return opts.outWidth to opts.outHeight
            }

            val before = thumbBounds(target.id)
            val bystanderThumbFile =
                VaultStorage.thumbFile(context, bystander.encryptedPath!!.substringAfterLast('/'))
            val bystanderStampBefore = bystanderThumbFile.lastModified()

            assertThat(repo.setRotation(target.id, 90)).isTrue()

            // The stored thumb was re-derived rotated: bounds swap. Encrypted at rest.
            val after = thumbBounds(target.id)
            assertThat(after).isEqualTo(before.second to before.first)
            assertNotPlaintext(
                VaultStorage.thumbFile(context, target.encryptedPath!!.substringAfterLast('/')),
            )
            // Single-item invalidation (§9): the other item's stored thumb is untouched.
            assertThat(bystanderThumbFile.lastModified()).isEqualTo(bystanderStampBefore)
            // Re-deriving from the stored thumb never decrypted a full-size blob.
            assertThat(repo.fullDecrypts.get()).isEqualTo(0)

            // Survives reopening: a fresh repository reads the orientation + rotated thumb.
            repo.delegate.lock()
            VaultThumbnailPipeline.clear()
            val reloaded = newRepo()
            val persisted = reloaded.allItems().first().first { it.id == target.id }
            assertThat(persisted.rotationDegrees).isEqualTo(90)

            // Navigate-away-and-back render: cached pipeline serves the rotated tile,
            // zero full decrypts, never a blank.
            val tile = VaultThumbnailPipeline.load(context, persisted, reloaded)
            assertThat(tile).isNotNull()
            assertThat(tile!!.width to tile.height).isEqualTo(after)
            assertThat(reloaded.fullDecrypts.get()).isEqualTo(0)

            // Committing the same net orientation again is an idempotent no-op write.
            val stampAfter =
                VaultStorage
                    .thumbFile(context, target.encryptedPath!!.substringAfterLast('/'))
                    .lastModified()
            assertThat(reloaded.setRotation(target.id, 90)).isTrue()
            assertThat(
                VaultStorage
                    .thumbFile(context, target.encryptedPath!!.substringAfterLast('/'))
                    .lastModified(),
            ).isEqualTo(stampAfter)
        }
}
