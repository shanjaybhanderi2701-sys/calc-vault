package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.GridSort
import com.appblish.calculatorvault.vault.model.SortDirection
import com.appblish.calculatorvault.vault.model.SortKey
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * W3-E organization-op semantics over the in-memory repository (the same index
 * arithmetic the device repository persists):
 *
 * - pin is one bit; a Bin'd album's tombstone sheds it so a restore returns the album
 *   **unpinned** (design G-2);
 * - the cover pointer clears the moment its item leaves the album — move, bin, permanent
 *   delete — and a later Bin restore never re-promotes it (design G-5);
 * - sort prefs are per grid type plus the per-album photo override (design G-8);
 * - rotation stores a normalized net orientation (spec §2.2), unknown ids report failure.
 */
class OrganizationOpsTest {
    private fun staged(
        id: String,
        sortKey: Long,
        folderId: String? = null,
    ) = VaultItem(
        id = id,
        category = VaultCategory.PHOTOS,
        originalName = "$id.jpg",
        dateLabel = "Today",
        sortKey = sortKey,
        folderId = folderId,
    )

    private fun repo() = InMemoryVaultContentRepository(seed = false)

    @Test
    fun `pin bit sets and clears and a binned album restores unpinned`() =
        runBlocking {
            val repo = repo()
            val album = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val item = repo.hide(listOf(staged("a", 1))).single()
            repo.moveToFolder(setOf(item.id), album.id)

            repo.setFolderPinned(album.id, true)
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .pinned
            ).isTrue()

            // Album → Bin keeps a tombstone; the pin does not survive it (G-2).
            repo.moveFoldersToRecycleBin(setOf(album.id))
            repo.restore(setOf(item.id))
            val restored = repo.folders(VaultCategory.PHOTOS).first().single()
            assertThat(restored.id).isEqualTo(album.id)
            assertThat(restored.pinned).isFalse()
        }

    @Test
    fun `cover pointer clears when its item leaves and never re-promotes on restore`() =
        runBlocking {
            val repo = repo()
            val album = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            // hide() assigns fresh vault ids — always address items by the returned ids.
            val items = repo.hide(listOf(staged("old", 1), staged("new", 2)))
            val old = items.first { it.originalName == "old.jpg" }.id
            val new = items.first { it.originalName == "new.jpg" }.id
            repo.moveToFolder(setOf(old, new), album.id)

            repo.setFolderCover(album.id, old)
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .coverItemId
            ).isEqualTo(old)

            // A pointer at a non-member is refused (stays on the current cover).
            repo.setFolderCover(album.id, "not-a-member")
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .coverItemId
            ).isEqualTo(old)

            // The cover item leaves for the Bin → the pointer drops (fallback = newest).
            repo.moveToRecycleBin(setOf(old))
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .coverItemId
            ).isNull()

            // Restoring the exact former cover does NOT re-promote it (G-5).
            repo.restore(setOf(old))
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .coverItemId
            ).isNull()

            // Move-away clears too.
            repo.setFolderCover(album.id, new)
            repo.moveToFolder(setOf(new), null)
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .coverItemId
            ).isNull()
        }

    @Test
    fun `sort prefs persist per grid type and the per-album override is independent`() =
        runBlocking {
            val repo = repo()
            val album = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            assertThat(repo.sortPrefs().first().photoSort).isEqualTo(GridSort.PHOTO_DEFAULT)
            assertThat(repo.sortPrefs().first().albumSort).isEqualTo(GridSort.ALBUM_DEFAULT)

            val sizeAsc = GridSort(SortKey.SIZE, SortDirection.ASCENDING)
            val modDesc = GridSort(SortKey.LAST_MODIFIED, SortDirection.DESCENDING)
            repo.setPhotoSort(sizeAsc)
            repo.setAlbumSort(modDesc)
            assertThat(repo.sortPrefs().first().photoSort).isEqualTo(sizeAsc)
            assertThat(repo.sortPrefs().first().albumSort).isEqualTo(modDesc)

            val nameDesc = GridSort(SortKey.NAME, SortDirection.DESCENDING)
            repo.setAlbumPhotoSortOverride(album.id, nameDesc)
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .photoSortOverride,
            ).isEqualTo(nameDesc)
            // The vault-wide choice is untouched by the override.
            assertThat(repo.sortPrefs().first().photoSort).isEqualTo(sizeAsc)

            repo.setAlbumPhotoSortOverride(album.id, null)
            assertThat(
                repo
                    .folders(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .photoSortOverride
            ).isNull()
        }

    @Test
    fun `rotation stores the normalized net orientation and unknown ids fail`() =
        runBlocking {
            val repo = repo()
            val item = repo.hide(listOf(staged("a", 1))).single()

            assertThat(repo.setRotation(item.id, 90)).isTrue()
            assertThat(
                repo
                    .allItems()
                    .first()
                    .single()
                    .rotationDegrees
            ).isEqualTo(90)

            // Net values normalize mod 360 (four taps = 0).
            assertThat(repo.setRotation(item.id, 450)).isTrue()
            assertThat(
                repo
                    .allItems()
                    .first()
                    .single()
                    .rotationDegrees
            ).isEqualTo(90)
            assertThat(repo.setRotation(item.id, 360)).isTrue()
            assertThat(
                repo
                    .allItems()
                    .first()
                    .single()
                    .rotationDegrees
            ).isEqualTo(0)

            assertThat(repo.setRotation("missing", 90)).isFalse()
        }
}
