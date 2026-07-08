package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.GridSort
import com.appblish.calculatorvault.vault.model.SortDirection
import com.appblish.calculatorvault.vault.model.SortKey
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.UnhideDisposition
import com.appblish.calculatorvault.vault.model.UnhideOutcome
import com.appblish.calculatorvault.vault.model.UnhideResult
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Locks in the folder-grid-first category state machine (APP-225, S10/S16/S17 + D-3/D-4)
 * and the W1-E3 multi-select layer: the root exposes folder tiles only (with a "Recent"
 * pseudo-folder so folder-less items are never hidden), open-folder/back is internal
 * ViewModel state, selection grows by long-press/tap/drag-range/Select All, Delete routes
 * through the two-choice dialog actions (bin vs permanent), and every bulk mutation —
 * Move, Unhide, Recycle Bin, permanent delete, plus the hide picker's "N hidden" hand-off
 * — surfaces exactly one per-operation summary notice (P2-3) — never silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Bulk ops run app-scoped in production (they must survive ViewModel clearing);
        // point that scope at the test scheduler so advanceUntilIdle drives them.
        BulkOps.scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        // The hide + bulk summaries are process-wide slots shared across ViewModels —
        // reset them so one test's pending summary can never leak into the next.
        HideImportViewModel.consumeHideSummary()
        BulkOps.consume()
        BulkOps.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Dispatchers.resetMain()
    }

    /** Delegating fake: real in-memory content store + a scriptable [UnhideResult]. */
    private class RecordingRepository(
        val delegate: InMemoryVaultContentRepository = InMemoryVaultContentRepository(seed = false),
        private val unhideResult: UnhideResult? = null,
    ) : VaultContentRepository by delegate {
        var unhiddenIds: Set<String>? = null
        var unhideDestination: UnhideDestination? = null

        override suspend fun unhideTo(
            itemIds: Set<String>,
            destination: UnhideDestination,
        ): UnhideResult {
            unhiddenIds = itemIds
            unhideDestination = destination
            return unhideResult ?: delegate.unhideTo(itemIds, destination)
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
    fun `album ordering keeps pinned above unpinned with the active sort inside each cluster`() {
        // W3-D §4/§7 (G-1): two clusters, key + direction within each, pin never a key.
        val tiles =
            listOf(
                CategoryFolderTile(id = "a", name = "beta", itemCount = 0, sizeBytes = 10, lastModifiedMs = 9),
                CategoryFolderTile(id = "b", name = "Alpha", itemCount = 0, sizeBytes = 30, lastModifiedMs = 1),
                CategoryFolderTile(
                    id = "c",
                    name = "Mid",
                    itemCount = 0,
                    sizeBytes = 20,
                    lastModifiedMs = 5,
                    pinned = true,
                ),
                CategoryFolderTile(
                    id = "d",
                    name = "zed",
                    itemCount = 0,
                    sizeBytes = 5,
                    lastModifiedMs = 7,
                    pinned = true,
                ),
            )
        val nameAsc = GridSort(SortKey.NAME, SortDirection.ASCENDING)
        assertThat(orderAlbumTiles(tiles, nameAsc).map { it.id }).containsExactly("c", "d", "b", "a").inOrder()
        val sizeDesc = GridSort(SortKey.SIZE, SortDirection.DESCENDING)
        assertThat(orderAlbumTiles(tiles, sizeDesc).map { it.id }).containsExactly("c", "d", "b", "a").inOrder()
        val modifiedAsc = GridSort(SortKey.LAST_MODIFIED, SortDirection.ASCENDING)
        assertThat(orderAlbumTiles(tiles, modifiedAsc).map { it.id }).containsExactly("c", "d", "b", "a").inOrder()
    }

    @Test
    fun `changing the album sort persists and reorders the root grid`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            repo.createFolder(VaultCategory.PHOTOS, "Alpha")
            repo.createFolder(VaultCategory.PHOTOS, "Beta")
            val vm = vm(repo)
            val nameDesc = GridSort(SortKey.NAME, SortDirection.DESCENDING)

            vm.setAlbumSort(nameDesc)

            val state = vm.state.first { it.albumSort == nameDesc && it.folderTiles.size == 2 }
            assertThat(state.folderTiles.map { it.name }).containsExactly("Beta", "Alpha").inOrder()
        }

    @Test
    fun `pin toggle from the single selection reorders the grid and unpin restores it`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            repo.createFolder(VaultCategory.PHOTOS, "Alpha")
            val zulu = repo.createFolder(VaultCategory.PHOTOS, "Zulu")
            val vm = vm(repo)
            vm.state.first { it.folderTiles.size == 2 }

            vm.startAlbumSelection(zulu.id)
            vm.state.first { it.selectedAlbumTiles.size == 1 }
            vm.togglePinSelectedAlbum()

            // Pinned cluster leads under the default Name·Ascending sort (G-1); the
            // selection exited with the write (the badge is the confirmation).
            var state = vm.state.first { it.folderTiles.firstOrNull()?.pinned == true }
            assertThat(state.folderTiles.map { it.name }).containsExactly("Zulu", "Alpha").inOrder()
            assertThat(state.albumSelectionMode).isFalse()

            vm.startAlbumSelection(zulu.id)
            vm.state.first { it.selectedAlbumTiles.size == 1 }
            vm.togglePinSelectedAlbum()
            state = vm.state.first { it.folderTiles.none { tile -> tile.pinned } }
            assertThat(state.folderTiles.map { it.name }).containsExactly("Alpha", "Zulu").inOrder()
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
    fun `bulk unhide surfaces the honest destination summary and consuming clears it`() =
        runTest(dispatcher) {
            val result =
                UnhideResult(
                    listOf(
                        UnhideOutcome("a", UnhideDisposition.REQUESTED),
                        UnhideOutcome("b", UnhideDisposition.FALLBACK, destinationLabel = "Downloads"),
                    ),
                )
            val repo = RecordingRepository(unhideResult = result)
            val stored = repo.hide(listOf(staged("a", sortKey = 2), staged("b", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 2 }

            vm.startSelection(stored[0].id)
            vm.toggle(stored[1].id)
            vm.unhideSelected(UnhideDestination.Original)
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(repo.unhiddenIds).containsExactly(stored[0].id, stored[1].id)
            assertThat(repo.unhideDestination).isEqualTo(UnhideDestination.Original)
            val state = vm.state.first { it.opNotice != null }
            assertThat(state.opNotice).isEqualTo("Unhid 1 · saved 1 to Downloads (original unavailable).")

            vm.consumeOpNotice()
            assertThat(vm.state.first { it.opNotice == null }.opNotice).isNull()
        }

    @Test
    fun `a fully failed bulk unhide is never silent`() =
        runTest(dispatcher) {
            val repo =
                RecordingRepository(
                    unhideResult = UnhideResult(listOf(UnhideOutcome("a", UnhideDisposition.FAILED))),
                )
            val stored = repo.hide(listOf(staged("a", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 1 }

            vm.startSelection(stored.single().id)
            vm.unhideSelected(UnhideDestination.Original)
            dispatcher.scheduler.advanceUntilIdle()

            val state = vm.state.first { it.opNotice != null }
            assertThat(state.opNotice).isEqualTo("Couldn't unhide — check storage access.")
        }

    @Test
    fun `select all selects every item in the open folder and toggles back off`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            repo.hide(listOf(staged("a", sortKey = 3), staged("b", sortKey = 2), staged("c", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 3 }

            vm.openFolder(CategoryState.RECENT_FOLDER_ID)
            vm.state.first { it.inFolder && it.folderItems.size == 3 }
            vm.selectAllInFolder()
            val selected = vm.state.first { it.selectionMode }
            assertThat(selected.selectedIds).hasSize(3)

            // The same control on a full selection clears it (toggle semantics).
            vm.selectAllInFolder()
            val cleared = vm.state.first { !it.selectionMode }
            assertThat(cleared.selectedIds).isEmpty()
        }

    @Test
    fun `drag select sweeps the display-order range and retreat releases only swept items`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            // Same dateLabel → display order is sortKey descending: a(5) b(4) c(3) d(2) e(1).
            val stored =
                repo.hide(
                    listOf(
                        staged("a", sortKey = 5),
                        staged("b", sortKey = 4),
                        staged("c", sortKey = 3),
                        staged("d", sortKey = 2),
                        staged("e", sortKey = 1),
                    ),
                )
            val byName = stored.associateBy { it.originalName.removeSuffix(".jpg") }

            fun id(name: String) = byName.getValue(name).id
            val vm = vm(repo)
            vm.state.first { it.items.size == 5 }
            vm.openFolder(CategoryState.RECENT_FOLDER_ID)
            vm.state.first { it.inFolder }

            // Long-press anchors on "b"; the drag sweeps forward to "d"…
            vm.beginDragSelect(id("b"))
            vm.dragSelectOver(id("d"))
            val swept = vm.state.first { it.selectedIds.size == 3 }
            assertThat(swept.selectedIds).containsExactly(id("b"), id("c"), id("d"))

            // …then retreats to "c": "d" drops out, the anchor stays.
            vm.dragSelectOver(id("c"))
            val retreated = vm.state.first { it.selectedIds.size == 2 }
            assertThat(retreated.selectedIds).containsExactly(id("b"), id("c"))

            // Gesture ends: the accumulated selection sticks, mode stays active.
            vm.endDragSelect()
            assertThat(vm.state.first { it.selectionMode }.selectedIds).containsExactly(id("b"), id("c"))
        }

    @Test
    fun `the tap ending a long-press gesture never un-selects the just-anchored item`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val stored = repo.hide(listOf(staged("a", sortKey = 1)))
            val id = stored.single().id
            val vm = vm(repo)
            vm.state.first { it.items.size == 1 }
            vm.openFolder(CategoryState.RECENT_FOLDER_ID)
            vm.state.first { it.inFolder }

            // Long press anchors; the same gesture's up fires the tile click — noise.
            vm.beginDragSelect(id)
            assertThat(vm.tappedItem(id)).isNull()
            vm.endDragSelect()
            assertThat(vm.state.first { it.selectionMode }.selectedIds).containsExactly(id)

            // After the gesture a real tap toggles normally…
            assertThat(vm.tappedItem(id)).isNull()
            assertThat(vm.state.first { !it.selectionMode }.selectedIds).isEmpty()
            // …and with no selection active, a tap opens the item.
            assertThat(vm.tappedItem(id)?.id).isEqualTo(id)
        }

    @Test
    fun `long-press on another item extends an active selection instead of resetting it`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val stored = repo.hide(listOf(staged("a", sortKey = 2), staged("b", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 2 }

            vm.startSelection(stored[0].id)
            vm.startSelection(stored[1].id)

            assertThat(vm.state.first { it.selectedIds.size == 2 }.selectedIds)
                .containsExactly(stored[0].id, stored[1].id)
        }

    @Test
    fun `bulk move surfaces a counted summary naming the destination folder`() =
        runTest(dispatcher) {
            val repo = RecordingRepository()
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Trip")
            val stored = repo.hide(listOf(staged("a", sortKey = 2), staged("b", sortKey = 1)))
            val vm = vm(repo)
            vm.state.first { it.items.size == 2 && it.folderTiles.any { tile -> tile.name == "Trip" } }

            vm.startSelection(stored[0].id)
            vm.toggle(stored[1].id)
            vm.moveSelectedToFolder(folder.id)
            dispatcher.scheduler.advanceUntilIdle()

            val state = vm.state.first { it.opNotice != null }
            assertThat(state.opNotice).isEqualTo("Moved 2 items to Trip")
            assertThat(
                repo
                    .items(VaultCategory.PHOTOS)
                    .first()
                    .filter { it.folderId == folder.id }
                    .map { it.id },
            ).containsExactly(stored[0].id, stored[1].id)
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
