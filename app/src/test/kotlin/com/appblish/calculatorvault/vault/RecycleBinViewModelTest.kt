package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.RecycleBin
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The recycle-bin screen's contract (W1-E4): purge-on-open, multi-select restore /
 * delete-forever through the app-scoped [BulkOps] executor, and the one-per-operation
 * "X done, Y failed" summary notice (spec §1.6 — one summary per bulk op, never silent).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecycleBinViewModelTest {
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
        // The bulk summary is a process-wide slot shared across ViewModels — reset it so
        // one test's pending summary can never leak into the next.
        BulkOps.consume()
        BulkOps.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Dispatchers.resetMain()
    }

    private fun staged(
        id: String,
        sortKey: Long = 1L,
    ) = VaultItem(
        id = id,
        category = VaultCategory.PHOTOS,
        originalName = "$id.jpg",
        dateLabel = "Today",
        sortKey = sortKey,
    )

    private suspend fun binWith(
        repo: InMemoryVaultContentRepository,
        vararg ids: String,
    ): List<String> {
        val stored = repo.hide(ids.map { staged(it) }).map { it.id }
        repo.moveToRecycleBin(stored.toSet())
        return stored
    }

    @Test
    fun `restoreSelected returns the items to the vault and reports the summary`() =
        runTest {
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored = binWith(repo, "a", "b")
            val vm = RecycleBinViewModel(repository = repo, clock = { 0L })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            stored.forEach { vm.toggle(it) }
            advanceUntilIdle()
            vm.restoreSelected()
            advanceUntilIdle()

            assertThat(repo.items(VaultCategory.PHOTOS).first()).hasSize(2)
            assertThat(repo.recycleBin().first()).isEmpty()
            assertThat(vm.state.value.opNotice).isEqualTo("2 items restored")
            assertThat(vm.state.value.selectedIds).isEmpty()
            vm.consumeOpNotice()
            advanceUntilIdle()
            assertThat(vm.state.value.opNotice).isNull()
            collector.cancel()
        }

    @Test
    fun `deleteSelectedForever destroys the entries and reports the summary`() =
        runTest {
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored = binWith(repo, "a")
            val vm = RecycleBinViewModel(repository = repo, clock = { 0L })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.toggle(stored.single())
            advanceUntilIdle()
            vm.deleteSelectedForever()
            advanceUntilIdle()

            assertThat(repo.recycleBin().first()).isEmpty()
            assertThat(repo.items(VaultCategory.PHOTOS).first()).isEmpty()
            assertThat(vm.state.value.opNotice).isEqualTo("1 item deleted forever")
            collector.cancel()
        }

    @Test
    fun `a selection that partially fails reports the failed count, never silent`() =
        runTest {
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored = binWith(repo, "a", "b")
            val vm = RecycleBinViewModel(repository = repo, clock = { 0L })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.selectAll()
            advanceUntilIdle()
            // One entry vanishes from the bin behind the screen's back (e.g. auto-purge on
            // another surface) after the selection was made but before the op runs.
            repo.deleteForever(setOf(stored[0]))
            // Note: state's validIds would drop it on the next emission, but the op reads
            // the selection it was invoked with — the repository's honest count covers it.
            vm.restoreSelected()
            advanceUntilIdle()

            assertThat(vm.state.value.opNotice).isEqualTo("1 item restored, 1 failed")
            collector.cancel()
        }

    @Test
    fun `opening the bin purges entries past the auto-delete window`() =
        runTest {
            val repo = InMemoryVaultContentRepository(seed = false)
            // InMemory stamps deletedAt from the item's sortKey — park one entry far past
            // the window and one inside it.
            val old = repo.hide(listOf(staged("old", sortKey = 0L))).single()
            val fresh = repo.hide(listOf(staged("fresh", sortKey = 1_000L))).single()
            repo.moveToRecycleBin(setOf(old.id, fresh.id))
            val windowMs = RecycleBin.AUTO_DELETE_WINDOW_DAYS * 24L * 60L * 60L * 1000L

            val vm = RecycleBinViewModel(repository = repo, clock = { windowMs + 500L })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val surviving = vm.state.value.entries
            assertThat(surviving.map { it.item.id }).containsExactly(fresh.id)
            collector.cancel()
        }

    @Test
    fun `selection survives only entries still in the bin`() =
        runTest {
            val repo = InMemoryVaultContentRepository(seed = false)
            val stored = binWith(repo, "a", "b")
            val vm = RecycleBinViewModel(repository = repo, clock = { 0L })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.selectAll()
            advanceUntilIdle()
            assertThat(vm.state.value.selectedIds).containsExactlyElementsIn(stored)

            // One entry leaves the bin behind the screen's back — its selection must drop.
            repo.restore(setOf(stored[0]))
            advanceUntilIdle()
            assertThat(vm.state.value.selectedIds).containsExactly(stored[1])
            collector.cancel()
        }
}
