package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.UnhideDisposition
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavioural contract for the W1-E2 single-photo actions at the repository seam (spec §1):
 * Move relocates the index entry only, Unhide reports a per-item result, and permanent
 * delete removes straight from the vault without routing through the Recycle Bin.
 */
class PhotoActionsRepositoryTest {
    private fun repo() = InMemoryVaultContentRepository(seed = false)

    private fun staged(
        id: String,
        category: VaultCategory = VaultCategory.PHOTOS,
        sortKey: Long = 1L,
    ) = VaultItem(id = id, category = category, originalName = "$id.jpg", dateLabel = "Today", sortKey = sortKey)

    @Test
    fun `move relocates the index entry only, keeping the item in the vault`() =
        runTest {
            val repo = repo()
            val id = repo.hide(listOf(staged("a"))).single().id
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Trip")

            repo.moveToFolder(setOf(id), folder.id)

            val items = repo.items(VaultCategory.PHOTOS).first()
            assertThat(items).hasSize(1)
            assertThat(items.single().folderId).isEqualTo(folder.id)
            // Still present + still the same encrypted reference — a move never touches the blob.
            assertThat(items.single().encryptedPath).isNotNull()
        }

    @Test
    fun `unhideTo reports one requested outcome per item and drops them from the vault`() =
        runTest {
            val repo = repo()
            val ids = repo.hide(listOf(staged("a", sortKey = 2), staged("b", sortKey = 1))).map { it.id }.toSet()

            val result = repo.unhideTo(ids, UnhideDestination.Original)

            assertThat(result.unhidden).isEqualTo(2)
            assertThat(result.outcomes.map { it.disposition })
                .containsExactly(UnhideDisposition.REQUESTED, UnhideDisposition.REQUESTED)
            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
        }

    @Test
    fun `permanentlyDelete removes from the vault without leaving a bin entry`() =
        runTest {
            val repo = repo()
            val id = repo.hide(listOf(staged("a"))).single().id

            repo.permanentlyDelete(setOf(id))

            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
            // Permanent is NOT a soft delete — nothing recoverable is left behind.
            assertThat(repo.recycleBin().first()).isEmpty()
        }

    @Test
    fun `move to bin keeps the item recoverable, unlike permanent delete`() =
        runTest {
            val repo = repo()
            val id = repo.hide(listOf(staged("a"))).single().id

            repo.moveToRecycleBin(setOf(id))

            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(repo.recycleBin().first()).hasSize(1)
            repo.restore(setOf(id))
            assertThat(repo.items(VaultCategory.PHOTOS).first()).hasSize(1)
        }
}
