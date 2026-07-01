package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InMemoryVaultContentRepositoryTest {
    private val day = 24L * 60L * 60L * 1000L

    private fun repo() = InMemoryVaultContentRepository(seed = false)

    private fun staged(
        id: String,
        category: VaultCategory,
        sortKey: Long,
    ) = VaultItem(id = id, category = category, originalName = "$id.bin", dateLabel = "Today", sortKey = sortKey)

    @Test
    fun `hide adds items and bumps the category count`() =
        runTest {
            val repo = repo()
            repo.hide(listOf(staged("a", VaultCategory.PHOTOS, 3), staged("b", VaultCategory.PHOTOS, 2)))
            assertThat(repo.categoryCounts().first()[VaultCategory.PHOTOS]).isEqualTo(2)
            assertThat(repo.items(VaultCategory.PHOTOS).first()).hasSize(2)
        }

    @Test
    fun `items come back newest first`() =
        runTest {
            val repo = repo()
            repo.hide(listOf(staged("old", VaultCategory.VIDEOS, 1), staged("new", VaultCategory.VIDEOS, 9)))
            val ids = repo.items(VaultCategory.VIDEOS).first().map { it.originalName }
            assertThat(ids.first()).isEqualTo("new.bin")
        }

    @Test
    fun `recycle then restore round-trips the item`() =
        runTest {
            val repo = repo()
            val stored = repo.hide(listOf(staged("a", VaultCategory.FILES, 5)))
            val id = stored.single().id
            repo.moveToRecycleBin(setOf(id))
            assertThat(repo.items(VaultCategory.FILES).first()).isEmpty()
            assertThat(repo.recycleBin().first()).hasSize(1)

            repo.restore(setOf(id))
            assertThat(repo.items(VaultCategory.FILES).first()).hasSize(1)
            assertThat(repo.recycleBin().first()).isEmpty()
        }

    @Test
    fun `unhide removes items from the vault and reports the count`() =
        runTest {
            val repo = repo()
            val stored =
                repo.hide(
                    listOf(
                        staged("a", VaultCategory.PHOTOS, 3),
                        staged("b", VaultCategory.PHOTOS, 2),
                    ),
                )
            val ids = stored.map { it.id }.toSet()

            val count = repo.unhide(ids)

            assertThat(count).isEqualTo(2)
            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
            // Un-hide is a restore-to-gallery, not a soft-delete: nothing lands in the bin.
            assertThat(repo.recycleBin().first()).isEmpty()
        }

    @Test
    fun `unhide leaves un-selected items in place`() =
        runTest {
            val repo = repo()
            val stored =
                repo.hide(
                    listOf(
                        staged("keep", VaultCategory.FILES, 3),
                        staged("go", VaultCategory.FILES, 2),
                    ),
                )
            val goId = stored.first { it.originalName == "go.bin" }.id

            val count = repo.unhide(setOf(goId))

            assertThat(count).isEqualTo(1)
            assertThat(repo.items(VaultCategory.FILES).first().map { it.originalName }).containsExactly("keep.bin")
        }

    @Test
    fun `delete forever removes from the bin permanently`() =
        runTest {
            val repo = repo()
            val id = repo.hide(listOf(staged("a", VaultCategory.AUDIOS, 5))).single().id
            repo.moveToRecycleBin(setOf(id))
            repo.deleteForever(setOf(id))
            assertThat(repo.recycleBin().first()).isEmpty()
            assertThat(repo.items(VaultCategory.AUDIOS).first()).isEmpty()
        }

    @Test
    fun `purge drops only entries past the auto-delete window`() =
        runTest {
            val repo = repo()
            // sortKey doubles as deletedAt in the in-memory bin; make one old, one fresh.
            val old = repo.hide(listOf(staged("old", VaultCategory.PHOTOS, 0))).single().id
            val fresh = repo.hide(listOf(staged("fresh", VaultCategory.PHOTOS, 1_000L * day))).single().id
            repo.moveToRecycleBin(setOf(old, fresh))

            // 5 days after `fresh` was binned: `old` (binned at t=0) is well past the
            // 30-day window, `fresh` is nowhere near it.
            val now = 1_000L * day + 5L * day
            val purged = repo.purgeExpired(now)
            assertThat(purged).isEqualTo(1)
            assertThat(repo.recycleBin().first().map { it.item.id }).containsExactly(fresh)
        }
}
