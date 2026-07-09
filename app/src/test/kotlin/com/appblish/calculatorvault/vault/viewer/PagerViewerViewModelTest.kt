package com.appblish.calculatorvault.vault.viewer

import com.appblish.calculatorvault.vault.CategoryState
import com.appblish.calculatorvault.vault.InMemoryVaultContentRepository
import com.appblish.calculatorvault.vault.VaultContentRepository
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
 * Locks in the pager viewer's contract (APP-225 board feedback, P0-2): the page set is
 * exactly the context the user opened the item from — a folder's items, or the category
 * root's folder-less items (folderId null / the "Recent" pseudo-folder) — in the grid's
 * newest-first order; the start index resolves to the tapped item and stays latched;
 * deleting the current item just shrinks the page set (the pager advances naturally);
 * an emptied context raises the [PagerViewerState.empty] signal; and the active page's
 * decrypt always resolves to content or an explicit error — never a silent blank.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PagerViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun staged(
        id: String,
        sortKey: Long,
        folderId: String? = null,
        category: VaultCategory = VaultCategory.PHOTOS,
    ) = VaultItem(
        id = id,
        category = category,
        originalName = "$id.jpg",
        dateLabel = "Today",
        sortKey = sortKey,
        folderId = folderId,
    )

    private fun vm(
        repository: InMemoryVaultContentRepository,
        startItemId: String,
        folderId: String? = null,
        category: VaultCategory = VaultCategory.PHOTOS,
    ) = PagerViewerViewModel(
        startItemId = startItemId,
        category = category,
        folderId = folderId,
        context = null,
        repository = repository,
    )

    @Test
    fun `folderId null pages the category root items newest first`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Download")
            val stored =
                repo.hide(
                    listOf(
                        staged("old-root", sortKey = 1),
                        staged("new-root", sortKey = 5),
                        staged("foldered", sortKey = 3, folderId = folder.id),
                        staged("other-cat", sortKey = 4, category = VaultCategory.VIDEOS),
                    ),
                )
            val startId = stored.first { it.originalName == "new-root.jpg" }.id

            val state = vm(repo, startItemId = startId).state.first { it.loaded }

            // Root context = folder-less items of this category only, in grid order.
            assertThat(state.pages.map { it.originalName })
                .containsExactly("new-root.jpg", "old-root.jpg")
                .inOrder()
            assertThat(state.startIndex).isEqualTo(0)
        }

    @Test
    fun `the Recent pseudo-folder id maps to the same root page set as null`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Download")
            val stored =
                repo.hide(
                    listOf(
                        staged("root", sortKey = 2),
                        staged("foldered", sortKey = 3, folderId = folder.id),
                    ),
                )
            val rootId = stored.first { it.originalName == "root.jpg" }.id

            val state =
                vm(repo, startItemId = rootId, folderId = CategoryState.RECENT_FOLDER_ID)
                    .state
                    .first { it.loaded }

            assertThat(state.pages.map { it.originalName }).containsExactly("root.jpg")
        }

    @Test
    fun `a folder context pages only that folder's items`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Download")
            val stored =
                repo.hide(
                    listOf(
                        staged("in-old", sortKey = 1, folderId = folder.id),
                        staged("in-new", sortKey = 5, folderId = folder.id),
                        staged("root", sortKey = 3),
                    ),
                )
            val startId = stored.first { it.originalName == "in-old.jpg" }.id

            val state = vm(repo, startItemId = startId, folderId = folder.id).state.first { it.loaded }

            assertThat(state.pages.map { it.originalName })
                .containsExactly("in-new.jpg", "in-old.jpg")
                .inOrder()
            // The tapped item is the second page of its folder.
            assertThat(state.startIndex).isEqualTo(1)
        }

    @Test
    fun `start index stays latched when the list shrinks and falls back to 0 when missing`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored =
                repo.hide(
                    listOf(
                        staged("a", sortKey = 3),
                        staged("b", sortKey = 2),
                        staged("c", sortKey = 1),
                    ),
                )
            val ids = stored.associateBy { it.originalName }

            val viewModel = vm(repo, startItemId = ids.getValue("b.jpg").id)
            assertThat(viewModel.state.first { it.loaded }.startIndex).isEqualTo(1)

            // Deleting the item ahead of the start item must not re-resolve the index —
            // the pager owns the live position; startIndex is initial-composition-only.
            repo.moveToRecycleBin(setOf(ids.getValue("a.jpg").id))
            val after = viewModel.state.first { it.pages.size == 2 }
            assertThat(after.startIndex).isEqualTo(1)

            // An unknown tapped id (already deleted) falls back to the first page.
            val fallback = vm(repo, startItemId = "gone").state.first { it.loaded }
            assertThat(fallback.startIndex).isEqualTo(0)
        }

    @Test
    fun `deleting the current item shrinks the page set in place with no bin residue`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored =
                repo.hide(
                    listOf(
                        staged("first", sortKey = 3),
                        staged("current", sortKey = 2),
                        staged("next", sortKey = 1),
                    ),
                )
            val currentId = stored.first { it.originalName == "current.jpg" }.id
            val viewModel = vm(repo, startItemId = currentId)
            viewModel.state.first { it.pages.size == 3 }

            viewModel.deletePermanently(currentId)
            dispatcher.scheduler.advanceUntilIdle()

            // The neighbours close ranks — the pager's slot now shows the next item.
            val state = viewModel.state.first { it.pages.size == 2 }
            assertThat(state.pages.map { it.originalName })
                .containsExactly("first.jpg", "next.jpg")
                .inOrder()
            assertThat(repo.recycleBin().first()).isEmpty()
        }

    @Test
    fun `emptying the context raises the empty signal`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val only = repo.hide(listOf(staged("only", sortKey = 1))).single()
            val viewModel = vm(repo, startItemId = only.id)
            val loaded = viewModel.state.first { it.pages.size == 1 }
            assertThat(loaded.empty).isFalse()

            viewModel.moveToRecycleBin(only.id)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.first { it.loaded && it.pages.isEmpty() }
            assertThat(state.empty).isTrue()
        }

    @Test
    fun `restoring the last item also empties the context`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val only = repo.hide(listOf(staged("only", sortKey = 1))).single()
            val viewModel = vm(repo, startItemId = only.id)
            viewModel.state.first { it.pages.size == 1 }

            viewModel.restore(only.id)
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.state.first { it.loaded && it.pages.isEmpty() }.empty).isTrue()
        }

    @Test
    fun `the active image page resolves to bytes and never stays blank`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val photo = repo.hide(listOf(staged("pic", sortKey = 1))).single()
            val viewModel = vm(repo, startItemId = photo.id)

            viewModel.setActivePage(photo.id)

            val active = viewModel.activePage.first { it != null && it.content != PageContent.Loading }
            assertThat(active?.itemId).isEqualTo(photo.id)
            assertThat(active?.content).isInstanceOf(PageContent.Bytes::class.java)
        }

    @Test
    fun `a failed decrypt surfaces an explicit error - unknown item and no-cache video`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val video =
                repo
                    .hide(listOf(staged("clip", sortKey = 1, category = VaultCategory.VIDEOS)))
                    .single()
            val viewModel = vm(repo, startItemId = video.id, category = VaultCategory.VIDEOS)

            // Unknown id: the item vanished between settle and decrypt.
            viewModel.setActivePage("gone")
            val missing = viewModel.activePage.first { it != null && it.content != PageContent.Loading }
            assertThat(missing?.content).isEqualTo(PageContent.Error)

            // Video with no cache dir (context null): the temp-file route cannot run.
            viewModel.setActivePage(video.id)
            val noCache =
                viewModel.activePage.first {
                    it != null && it.itemId == video.id && it.content != PageContent.Loading
                }
            assertThat(noCache?.content).isEqualTo(PageContent.Error)
        }

    @Test
    fun `re-settling on the same page does not restart its decrypt`() =
        runTest(dispatcher) {
            val repo = InMemoryVaultContentRepository(seed = false)
            val photo = repo.hide(listOf(staged("pic", sortKey = 1))).single()
            val viewModel = vm(repo, startItemId = photo.id)

            viewModel.setActivePage(photo.id)
            val first = viewModel.activePage.first { it != null && it.content != PageContent.Loading }

            // Same id again (e.g. the pages list re-emitted): stays resolved, no Loading flip.
            viewModel.setActivePage(photo.id)
            assertThat(viewModel.activePage.value).isSameInstanceAs(first)
        }

    @Test
    fun `swiping back to a viewed page reuses the in-session cache with no second decrypt`() =
        runTest(dispatcher) {
            // APP-293 P0-4: the viewer cache — decrypt each page once per session.
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored = repo.hide(listOf(staged("first", sortKey = 2), staged("second", sortKey = 1)))
            var decrypts = 0
            val counting =
                object : VaultContentRepository by repo {
                    override suspend fun openDecrypted(itemId: String): ByteArray? {
                        decrypts++
                        return repo.openDecrypted(itemId)
                    }
                }
            val viewModel =
                PagerViewerViewModel(
                    startItemId = stored[0].id,
                    category = VaultCategory.PHOTOS,
                    folderId = null,
                    context = null,
                    repository = counting,
                )

            viewModel.setActivePage(stored[0].id)
            viewModel.activePage.first { it?.itemId == stored[0].id && it.content != PageContent.Loading }
            viewModel.setActivePage(stored[1].id)
            viewModel.activePage.first { it?.itemId == stored[1].id && it.content != PageContent.Loading }
            assertThat(decrypts).isEqualTo(2)

            // Swipe back: served from the in-session cache — instantly (no Loading state)
            // and with no third decrypt.
            viewModel.setActivePage(stored[0].id)
            val back = viewModel.activePage.value
            assertThat(back?.itemId).isEqualTo(stored[0].id)
            assertThat(back?.content).isInstanceOf(PageContent.Bytes::class.java)
            assertThat(decrypts).isEqualTo(2)
        }

    @Test
    fun `settling pre-decrypts both neighbours into the window as bytes`() =
        runTest(dispatcher) {
            // APP-314 P0 — the gap the existing (green) cache test does NOT cover: settling on
            // n must leave n-1 AND n+1 already holding Bytes (not Loading) so a swipe is instant
            // in either direction. Single-active-page never pre-decrypted the forward neighbour.
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored =
                repo.hide(
                    listOf(
                        staged("a", sortKey = 3),
                        staged("b", sortKey = 2),
                        staged("c", sortKey = 1),
                    ),
                )
            val ids = stored.associate { it.originalName to it.id }
            val viewModel = vm(repo, startItemId = ids.getValue("b.jpg"))
            // Load the page set so the window has real neighbours (a, c) to reach for.
            viewModel.state.first { it.loaded && it.pages.size == 3 }

            viewModel.setActivePage(ids.getValue("b.jpg"))
            dispatcher.scheduler.advanceUntilIdle()

            // Newest-first order is [a, b, c]; settling on the middle page b pre-decrypts BOTH
            // neighbours — every windowed page is Bytes, none is left on PageContent.Loading.
            val window = viewModel.pageWindow.value
            assertThat(window[ids.getValue("a.jpg")]).isInstanceOf(PageContent.Bytes::class.java)
            assertThat(window[ids.getValue("b.jpg")]).isInstanceOf(PageContent.Bytes::class.java)
            assertThat(window[ids.getValue("c.jpg")]).isInstanceOf(PageContent.Bytes::class.java)
        }

    @Test
    fun `a forward-then-back slide never re-decrypts any revisited page`() =
        runTest(dispatcher) {
            // APP-314 P0 proof #1 (fullDecrypts discipline): A→B→C→B→A decrypts each distinct
            // page exactly once — the pre-decrypt window + LRU serve every revisit from cache.
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored =
                repo.hide(
                    listOf(
                        staged("a", sortKey = 3),
                        staged("b", sortKey = 2),
                        staged("c", sortKey = 1),
                    ),
                )
            val ids = stored.associate { it.originalName to it.id }
            val decryptCounts = mutableMapOf<String, Int>()
            val counting =
                object : VaultContentRepository by repo {
                    override suspend fun openDecrypted(itemId: String): ByteArray? {
                        decryptCounts[itemId] = (decryptCounts[itemId] ?: 0) + 1
                        return repo.openDecrypted(itemId)
                    }
                }
            val viewModel =
                PagerViewerViewModel(
                    startItemId = ids.getValue("a.jpg"),
                    category = VaultCategory.PHOTOS,
                    folderId = null,
                    context = null,
                    repository = counting,
                )
            viewModel.state.first { it.loaded && it.pages.size == 3 }

            listOf("a.jpg", "b.jpg", "c.jpg", "b.jpg", "a.jpg").forEach { name ->
                viewModel.setActivePage(ids.getValue(name))
                dispatcher.scheduler.advanceUntilIdle()
            }

            assertThat(decryptCounts).containsExactly(
                ids.getValue("a.jpg"), 1,
                ids.getValue("b.jpg"), 1,
                ids.getValue("c.jpg"), 1,
            )
        }
}
