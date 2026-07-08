package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.RestoreSummary
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Locks in the folder-grid-first category state machine (APP-225, S10/S16/S17 + D-3/D-4):
 * the root exposes folder tiles only (with a "Recent" pseudo-folder so folder-less items
 * are never hidden), open-folder/back is internal ViewModel state, Delete routes through
 * the two-choice dialog actions (bin vs permanent), and every mutation — Restore, Recycle
 * Bin, permanent delete, plus the hide picker's "N hidden" hand-off — surfaces exactly one
 * per-operation summary notice (P2-3) — never silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        // The hide summary is a process-wide slot shared across ViewModels — reset it so
        // one test's pending summary can never leak into the next.
        HideImportViewModel.consumeHideSummary()
        Dispatchers.resetMain()
    }

    /** Delegating fake: real in-memory content store + a scriptable [RestoreSummary]. */
    private class RecordingRepository(
        val delegate: InMemoryVaultContentRepository = InMemoryVaultContentRepository(seed = false),
        private val restoreSummary: RestoreSummary? = null,
    ) : VaultContentRepository by delegate {
        var restoredIds: Set<String>? = null

        override suspend fun unhideDetailed(itemIds: Set<String>): RestoreSummary {
            restoredIds = itemIds
            return restoreSummary ?: delegate.unhideDetailed(itemIds)
        }
    }

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

    private fun vm(repository: VaultContentRepository) = CategoryViewModel(VaultCategory.PHOTOS, repository)

    @Test
    fun `root grid shows folder tiles with live counts and covers plus Recent pinned first`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Download")
            repo.hide(
                listOf(
                    staged("in-old", sortKey = 1, folderId = folder.id),
                    staged("in-new", sortKey = 5, folderId = folder.id),
                    staged("rootitem", sortKey = 3),
                ),
            )

            val state = vm(repo).state.first { it.folderTiles.isNotEmpty() }

            // "Recent" (the folder-less items) leads so legacy items are never hidden.
            assertThat(state.folderTiles.map { it.name }).containsExactly("Recent", "Download").inOrder()
            val recent = state.folderTiles.first()
            assertThat(recent.id).isEqualTo(CategoryState.RECENT_FOLDER_ID)
            assertThat(recent.itemCount).isEqualTo(1)
            assertThat(recent.cover?.originalName).isEqualTo("rootitem.jpg")
            val download = state.folderTiles.last()
            assertThat(download.itemCount).isEqualTo(2)
            // Cover is the newest contained item.
            assertThat(download.cover?.originalName).isEqualTo("in-new.jpg")
        }

    @Test
    fun `empty seeded folder shows a zero-count tile with no cover and no Recent tile`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            repo.createFolder(VaultCategory.PHOTOS, "Download")

            val state = vm(repo).state.first { it.folderTiles.isNotEmpty() }

            val tile = state.folderTiles.single()
            assertThat(tile.name).isEqualTo("Download")
            assertThat(tile.itemCount).isEqualTo(0)
            assertThat(tile.cover).isNull()
        }

    @Test
    fun `folder sort orders tiles by name and date in both directions`() {
        val tiles =
            listOf(
                CategoryFolderTile(id = "a", name = "beta", itemCount = 0, newestSortKey = 9),
                CategoryFolderTile(id = "b", name = "Alpha", itemCount = 0, newestSortKey = 1),
            )
        assertThat(FolderSort.NAME_ASC.sorted(tiles).map { it.name }).containsExactly("Alpha", "beta").inOrder()
        assertThat(FolderSort.NAME_DESC.sorted(tiles).map { it.name }).containsExactly("beta", "Alpha").inOrder()
        assertThat(FolderSort.DATE_DESC.sorted(tiles).map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(FolderSort.DATE_ASC.sorted(tiles).map { it.id }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `changing the folder sort reorders the root grid`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            repo.createFolder(VaultCategory.PHOTOS, "Alpha")
            repo.createFolder(VaultCategory.PHOTOS, "Beta")
            val vm = vm(repo)

            vm.setFolderSort(FolderSort.NAME_DESC)

            val state = vm.state.first { it.folderSort == FolderSort.NAME_DESC && it.folderTiles.size == 2 }
            assertThat(state.folderTiles.map { it.name }).containsExactly("Beta", "Alpha").inOrder()
        }

    @Test
    fun `opening a folder scopes items to it and back returns to the root grid`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Download")
            repo.hide(
                listOf(
                    staged("inside", sortKey = 2, folderId = folder.id),
                    staged("outside", sortKey = 1),
                ),
            )
            val vm = vm(repo)

            vm.openFolder(folder.id)
            val open = vm.state.first { it.inFolder }
            assertThat(open.openFolderTitle).isEqualTo("Download")
            assertThat(open.folderItems.map { it.originalName }).containsExactly("inside.jpg")

            vm.closeFolder()
            val closed = vm.state.first { !it.inFolder }
            // Back at the root nothing is item-scoped; the grid shows folders only.
            assertThat(closed.folderItems).isEmpty()
            assertThat(closed.selectionMode).isFalse()
        }

    @Test
    fun `the Recent pseudo-folder opens onto the folder-less items`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            repo.createFolder(VaultCategory.PHOTOS, "Download")
            repo.hide(listOf(staged("legacy", sortKey = 1)))
            val vm = vm(repo)

            vm.openFolder(CategoryState.RECENT_FOLDER_ID)

            val state = vm.state.first { it.inFolder }
            assertThat(state.openFolderTitle).isEqualTo("Recent")
            assertThat(state.folderItems.map { it.originalName }).containsExactly("legacy.jpg")
        }

    @Test
    fun `delete choice - move to recycle bin sends the selection to the bin and clears it`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val stored = repo.hide(listOf(staged("keep", sortKey = 2), staged("bin", sortKey = 1)))
            val binId = stored.first { it.originalName == "bin.jpg" }.id
            val vm = vm(repo)
            vm.state.first { it.items.size == 2 }

            vm.startSelection(binId)
            vm.recycleSelected()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(repo.recycleBin().first().map { it.item.originalName }).containsExactly("bin.jpg")
            val state = vm.state.first { it.items.size == 1 }
            assertThat(state.selectionMode).isFalse()
            assertThat(state.selectedIds).isEmpty()
        }

    @Test
    fun `delete choice - delete permanently destroys the selection with no bin residue`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val stored = repo.hide(listOf(staged("keep", sortKey = 2), staged("gone", sortKey = 1)))
            val goneId = stored.first { it.originalName == "gone.jpg" }.id
            val vm = vm(repo)
            vm.state.first { it.items.size == 2 }

            vm.startSelection(goneId)
            vm.deleteSelectedForever()
            dispatcher.scheduler.advanceUntilIdle()

            val state = vm.state.first { it.items.size == 1 }
            assertThat(state.items.single().originalName).isEqualTo("keep.jpg")
            // Permanent delete is bin-routed internally but leaves nothing recoverable.
            assertThat(repo.recycleBin().first()).isEmpty()
        }

    @Test
    fun `restore surfaces one summary notice and consuming clears it`() =
        runTest(dispatcher) {
            val summary =
                RestoreSummary(
                    restoredToOriginal = 1,
                    restoredToFallback = 1,
                    fallbackDestination = "DCIM/Restored",
                )
            val repo = RecordingRepository(restoreSummary = summary)
            val stored = repo.hide(listOf(staged("a", sortKey = 2), staged("b", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 2 }

            vm.startSelection(stored[0].id)
            vm.toggle(stored[1].id)
            vm.restoreSelected()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(repo.restoredIds).containsExactly(stored[0].id, stored[1].id)
            val state = vm.state.first { it.opNotice != null }
            assertThat(state.opNotice).isEqualTo(summary.noticeText())

            vm.consumeOpNotice()
            assertThat(vm.state.first { it.opNotice == null }.opNotice).isNull()
        }

    @Test
    fun `a fully failed restore is never silent`() =
        runTest(dispatcher) {
            val repo = RecordingRepository(restoreSummary = RestoreSummary(failed = 1))
            val stored = repo.hide(listOf(staged("a", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 1 }

            vm.startSelection(stored.single().id)
            vm.restoreSelected()
            dispatcher.scheduler.advanceUntilIdle()

            val state = vm.state.first { it.opNotice != null }
            assertThat(state.opNotice).isEqualTo("Couldn't restore — check storage access")
        }

    @Test
    fun `moving to the recycle bin surfaces a counted summary notice`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val stored = repo.hide(listOf(staged("a", sortKey = 2), staged("b", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 2 }

            vm.startSelection(stored[0].id)
            vm.toggle(stored[1].id)
            vm.recycleSelected()
            dispatcher.scheduler.advanceUntilIdle()

            val state = vm.state.first { it.opNotice != null }
            assertThat(state.opNotice).isEqualTo("2 items moved to Recycle Bin")

            vm.consumeOpNotice()
            assertThat(vm.state.first { it.opNotice == null }.opNotice).isNull()
        }

    @Test
    fun `permanent delete surfaces a counted summary notice with singular copy`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val stored = repo.hide(listOf(staged("keep", sortKey = 2), staged("gone", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 2 }

            vm.startSelection(stored.first { it.originalName == "gone.jpg" }.id)
            vm.deleteSelectedForever()
            dispatcher.scheduler.advanceUntilIdle()

            val state = vm.state.first { it.opNotice != null }
            assertThat(state.opNotice).isEqualTo("1 item deleted")
        }

    @Test
    fun `a pending hide summary from the picker surfaces here and consuming clears both slots`() =
        runTest(dispatcher) {
            // The picker pops immediately after hiding, so its summary travels through the
            // shared HideImportViewModel slot and the category screen renders it (P2-3).
            HideImportViewModel.publishHideSummary("3 hidden")
            val vm = vm(RecordingRepository())

            val state = vm.state.first { it.opNotice != null }
            assertThat(state.opNotice).isEqualTo("3 hidden")

            vm.consumeOpNotice()
            assertThat(HideImportViewModel.hideSummary.value).isNull()
            assertThat(vm.state.first { it.opNotice == null }.opNotice).isNull()
        }
}
