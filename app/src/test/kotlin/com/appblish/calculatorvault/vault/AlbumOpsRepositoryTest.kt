package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavioural contract for the W2-E album-level actions at the repository seam (spec §3):
 * rename is a label-only index write, album move is a merge that removes emptied sources,
 * album unhide drops only fully-emptied labels, delete-to-Bin keeps the album grouping
 * behind an inBin tombstone so a restore brings the album back whole (design F-3), and
 * permanent delete drops contents + label for good.
 */
class AlbumOpsRepositoryTest {
    private var clock = 1_000L

    private fun repo() = InMemoryVaultContentRepository(seed = false, clock = { clock })

    private fun staged(
        id: String,
        sortKey: Long = 1L,
    ) = VaultItem(
        id = id,
        category = VaultCategory.PHOTOS,
        originalName = "$id.jpg",
        dateLabel = "Today",
        sortKey = sortKey,
        sizeBytes = 100L,
    )

    @Test
    fun `renameFolder updates the label and modified stamp, nothing else`() =
        runTest {
            val repo = repo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val id = repo.hide(listOf(staged("a"))).single().id
            repo.moveToFolder(setOf(id), folder.id)

            clock = 2_000L
            repo.renameFolder(folder.id, "Holiday")

            val renamed = repo.folders(VaultCategory.PHOTOS).first().single()
            assertThat(renamed.id).isEqualTo(folder.id)
            assertThat(renamed.name).isEqualTo("Holiday")
            assertThat(renamed.createdAt).isEqualTo(1_000L)
            assertThat(renamed.modifiedAt).isEqualTo(2_000L)
            // The member item is untouched — same id, same folder, same blob reference.
            val item = repo.items(VaultCategory.PHOTOS).first().single()
            assertThat(item.folderId).isEqualTo(folder.id)
            assertThat(item.encryptedPath).isNotNull()
        }

    @Test
    fun `mergeFolders relocates members to the target and removes the emptied source label`() =
        runTest {
            val repo = repo()
            val source = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val target = repo.createFolder(VaultCategory.PHOTOS, "Screenshots")
            val ids = repo.hide(listOf(staged("a"), staged("b"))).map { it.id }
            repo.moveToFolder(ids.toSet(), source.id)

            val moved = repo.mergeFolders(setOf(source.id), target.id)

            assertThat(moved).isEqualTo(2)
            val items = repo.items(VaultCategory.PHOTOS).first()
            assertThat(items.map { it.folderId }.distinct()).containsExactly(target.id)
            assertThat(repo.folders(VaultCategory.PHOTOS).first().map { it.id }).containsExactly(target.id)
        }

    @Test
    fun `mergeFolders never treats the target as a source`() =
        runTest {
            val repo = repo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val id = repo.hide(listOf(staged("a"))).single().id
            repo.moveToFolder(setOf(id), folder.id)

            val moved = repo.mergeFolders(setOf(folder.id), folder.id)

            assertThat(moved).isEqualTo(0)
            assertThat(repo.folders(VaultCategory.PHOTOS).first().map { it.id }).containsExactly(folder.id)
            assertThat(
                repo
                    .items(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .folderId
            ).isEqualTo(folder.id)
        }

    @Test
    fun `unhideFolders unhides members and removes the emptied album label`() =
        runTest {
            val repo = repo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val keep = repo.createFolder(VaultCategory.PHOTOS, "Keep")
            val ids = repo.hide(listOf(staged("a"), staged("b"))).map { it.id }
            repo.moveToFolder(ids.toSet(), folder.id)
            val keptId = repo.hide(listOf(staged("c"))).single().id
            repo.moveToFolder(setOf(keptId), keep.id)

            val result = repo.unhideFolders(setOf(folder.id), UnhideDestination.Original)

            assertThat(result.unhidden).isEqualTo(2)
            assertThat(repo.folders(VaultCategory.PHOTOS).first().map { it.id }).containsExactly(keep.id)
            assertThat(repo.items(VaultCategory.PHOTOS).first().map { it.id }).containsExactly(keptId)
        }

    @Test
    fun `moveFoldersToRecycleBin keeps grouping and restore brings the album back whole`() =
        runTest {
            val repo = repo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val ids = repo.hide(listOf(staged("a"), staged("b"))).map { it.id }
            repo.moveToFolder(ids.toSet(), folder.id)

            val binned = repo.moveFoldersToRecycleBin(setOf(folder.id))

            assertThat(binned).isEqualTo(2)
            // The album vanishes from every album surface…
            assertThat(repo.folders(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(repo.folderCounts().first()[VaultCategory.PHOTOS]).isEqualTo(0)
            // …but the bin entries keep their grouping (design F-3).
            val bin = repo.recycleBin().first()
            assertThat(bin.map { it.item.folderId }.distinct()).containsExactly(folder.id)

            val restored = repo.restore(ids.toSet())

            assertThat(restored).isEqualTo(2)
            val back = repo.folders(VaultCategory.PHOTOS).first().single()
            assertThat(back.id).isEqualTo(folder.id)
            assertThat(back.name).isEqualTo("Camera")
            assertThat(back.inBin).isFalse()
            assertThat(
                repo
                    .items(VaultCategory.PHOTOS)
                    .first()
                    .map { it.folderId }
                    .distinct()
            ).containsExactly(folder.id)
        }

    @Test
    fun `deleteForever on the last bin entry drops the album tombstone for good`() =
        runTest {
            val repo = repo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val ids = repo.hide(listOf(staged("a"), staged("b"))).map { it.id }
            repo.moveToFolder(ids.toSet(), folder.id)
            repo.moveFoldersToRecycleBin(setOf(folder.id))

            repo.deleteForever(setOf(ids[0]))
            // One entry still references the album — the tombstone must survive.
            repo.restore(setOf(ids[1]))

            val back = repo.folders(VaultCategory.PHOTOS).first().single()
            assertThat(back.id).isEqualTo(folder.id)

            // Bin the album again and destroy the remaining entry: nothing references the
            // label any more, so the record is gone — a fresh restore has nothing to revive.
            repo.moveFoldersToRecycleBin(setOf(folder.id))
            repo.deleteForever(setOf(ids[1]))
            assertThat(repo.folders(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(repo.recycleBin().first()).isEmpty()
        }

    @Test
    fun `restore of an album deleted outright falls back to the category root`() =
        runTest {
            val repo = repo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val id = repo.hide(listOf(staged("a"))).single().id
            repo.moveToFolder(setOf(id), folder.id)
            // Photo binned individually, then the (now empty) album label deleted outright.
            repo.moveToRecycleBin(setOf(id))
            repo.deleteFolderLabels(setOf(folder.id), keepForBinRestore = false)

            repo.restore(setOf(id))

            assertThat(
                repo
                    .items(VaultCategory.PHOTOS)
                    .first()
                    .single()
                    .folderId
            ).isNull()
            assertThat(repo.folders(VaultCategory.PHOTOS).first()).isEmpty()
        }

    @Test
    fun `permanentlyDeleteFolders destroys contents and labels without touching the bin`() =
        runTest {
            val repo = repo()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val keep = repo.createFolder(VaultCategory.PHOTOS, "Keep")
            val ids = repo.hide(listOf(staged("a"), staged("b"))).map { it.id }
            repo.moveToFolder(ids.toSet(), folder.id)

            val erased = repo.permanentlyDeleteFolders(setOf(folder.id))

            assertThat(erased).isEqualTo(2)
            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(repo.folders(VaultCategory.PHOTOS).first().map { it.id }).containsExactly(keep.id)
            assertThat(repo.recycleBin().first()).isEmpty()
        }

    @Test
    fun `empty album delete just removes the label — soft and hard paths alike`() =
        runTest {
            val repo = repo()
            val a = repo.createFolder(VaultCategory.PHOTOS, "EmptyA")
            val b = repo.createFolder(VaultCategory.PHOTOS, "EmptyB")

            assertThat(repo.moveFoldersToRecycleBin(setOf(a.id))).isEqualTo(0)
            assertThat(repo.permanentlyDeleteFolders(setOf(b.id))).isEqualTo(0)

            assertThat(repo.folders(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(repo.recycleBin().first()).isEmpty()
        }
}
