package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the hide-flow picker's selection semantics (APP-225, sign-off S14–S16):
 * the folder-grid "All" toggle, per-date-section select, the live "Selected - N" title,
 * and the S16 source-bucket → vault-folder mapping at hide time. The view model runs on
 * sample data (no [com.appblish.calculatorvault.vault.media.MediaSource]) against a fake
 * repository, so every path is deterministic and Android-free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HideImportViewModelTest {
    /** Records hides and folder creations; folders live in a real flow like the impls. */
    private class FakeRepository : VaultContentRepository {
        val foldersState = MutableStateFlow<List<VaultFolder>>(emptyList())
        val hidden = mutableListOf<VaultItem>()
        val createdFolderNames = mutableListOf<String>()
        private var nextId = 1

        fun seedFolder(
            category: VaultCategory,
            name: String,
        ): VaultFolder {
            val folder = VaultFolder(id = "seed_${name.lowercase()}", category = category, name = name)
            foldersState.value = foldersState.value + folder
            return folder
        }

        fun folderName(id: String?): String? = foldersState.value.firstOrNull { it.id == id }?.name

        override fun items(category: VaultCategory): Flow<List<VaultItem>> = flowOf(emptyList())

        override fun allItems(): Flow<List<VaultItem>> = flowOf(emptyList())

        override fun folders(category: VaultCategory): Flow<List<VaultFolder>> =
            foldersState.map { list -> list.filter { it.category == category } }

        override fun categoryCounts(): Flow<Map<VaultCategory, Int>> = flowOf(emptyMap())

        override fun recent(limit: Int): Flow<List<VaultItem>> = flowOf(emptyList())

        override fun recycleBin(): Flow<List<RecycleBinEntry>> = flowOf(emptyList())

        override suspend fun hide(items: List<VaultItem>): List<VaultItem> {
            hidden += items
            return items
        }

        override suspend fun createFolder(
            category: VaultCategory,
            name: String,
        ): VaultFolder {
            createdFolderNames += name
            val folder = VaultFolder(id = "f${nextId++}", category = category, name = name)
            foldersState.value = foldersState.value + folder
            return folder
        }

        override suspend fun moveToFolder(
            itemIds: Set<String>,
            folderId: String?,
        ) = Unit

        override suspend fun unhide(itemIds: Set<String>): Int = 0

        override suspend fun moveToRecycleBin(itemIds: Set<String>) = Unit

        override suspend fun restore(itemIds: Set<String>) = Unit

        override suspend fun deleteForever(itemIds: Set<String>) = Unit

        override suspend fun purgeExpired(now: Long): Int = 0

        override suspend fun openDecrypted(itemId: String): ByteArray? = null
    }

    private lateinit var repository: FakeRepository

    @Before
    fun setUp() {
        // viewModelScope launches on Main.immediate; Unconfined makes them run eagerly.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(category: VaultCategory = VaultCategory.PHOTOS) =
        HideImportViewModel(category = category, repository = repository, mediaSource = null)

    // --- S14: folder-grid "All" toggle + per-tile selection ---

    @Test
    fun allToggle_onFolderGrid_selectsEveryRealFolder_thenClears() {
        val vm = viewModel()
        // Sample photos albums: Recent aggregate + Camera / Screenshots / Download.
        vm.toggleAllFolders()
        assertEquals(setOf("camera", "screenshots", "downloads"), vm.state.value.selectedFolderIds)
        assertTrue(vm.state.value.allFoldersSelected)
        assertTrue(vm.state.value.hideEnabled)

        vm.toggleAllFolders()
        assertEquals(emptySet<String>(), vm.state.value.selectedFolderIds)
        assertFalse(vm.state.value.hideEnabled)
    }

    @Test
    fun recentAggregate_isNeverFolderSelectable() {
        val vm = viewModel()
        vm.toggleFolder(SourceAlbum.RECENT_ID)
        assertEquals(emptySet<String>(), vm.state.value.selectedFolderIds)
        // And the "All" universe excludes it too.
        assertFalse(SourceAlbum.RECENT_ID in vm.state.value.selectableFolderIds)
    }

    // --- S15: "All" toggle, per-section select, live title ---

    @Test
    fun allToggle_onItemGrid_selectsAndClearsEveryItem() {
        val vm = viewModel()
        vm.selectAlbum("camera")
        vm.toggleAll()
        assertEquals(9, vm.state.value.selectedIds.size)
        assertTrue(vm.state.value.allItemsSelected)

        vm.toggleAll()
        assertEquals(emptySet<String>(), vm.state.value.selectedIds)
    }

    @Test
    fun toggleSection_selectsAndClearsOnlyThatSection() {
        val vm = viewModel()
        vm.selectAlbum("camera")
        // Sample data: three items per date section ("Today" = camera-0..2).
        val today = listOf("camera-0", "camera-1", "camera-2")
        val yesterdayItem = "camera-3"
        vm.toggle(yesterdayItem)

        vm.toggleSection(today)
        assertEquals(today.toSet() + yesterdayItem, vm.state.value.selectedIds)

        // A partially-selected section re-selects fully first…
        vm.toggle("camera-1")
        vm.toggleSection(today)
        assertEquals(today.toSet() + yesterdayItem, vm.state.value.selectedIds)

        // …and a fully-selected one clears, leaving other sections' picks intact.
        vm.toggleSection(today)
        assertEquals(setOf(yesterdayItem), vm.state.value.selectedIds)
    }

    @Test
    fun pickerTitle_isLiveSelectedCount_insideFolder() {
        val vm = viewModel()
        assertEquals("Hide photos", vm.state.value.pickerTitle)

        vm.selectAlbum("camera")
        assertEquals("Selected - 0", vm.state.value.pickerTitle)

        vm.toggle("camera-0")
        vm.toggle("camera-1")
        assertEquals("Selected - 2", vm.state.value.pickerTitle)

        vm.clearAlbum()
        assertEquals("Hide photos", vm.state.value.pickerTitle)
    }

    // --- S16: source-bucket → vault-folder mapping at hide time ---

    @Test
    fun hideNow_reusesExistingVaultFolderMatchingBucketName() {
        val seeded = repository.seedFolder(VaultCategory.PHOTOS, "Camera")
        val vm = viewModel()
        vm.selectAlbum("camera")
        vm.toggleAll()
        vm.hideNow()

        assertEquals(9, repository.hidden.size)
        repository.hidden.forEach { assertEquals(seeded.id, it.folderId) }
        assertEquals(emptyList<String>(), repository.createdFolderNames)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun hideNow_fromRecent_createsFoldersNamedAfterRealSourceBuckets() {
        val vm = viewModel()
        vm.selectAlbum(SourceAlbum.RECENT_ID)
        vm.toggleAll()
        vm.hideNow()

        // Recent sample items cycle through the real buckets — one vault folder per
        // distinct bucket, never a folder literally named "Recent".
        assertEquals(setOf("Camera", "Screenshots", "Download"), repository.createdFolderNames.toSet())
        assertFalse(repository.foldersState.value.any { it.name == "Recent" })
        // Every hidden item landed in the folder named after its own source bucket.
        val sources = vm.state.value.sources
            .associateBy { it.id }
        repository.hidden.forEach { item ->
            assertEquals(sources.getValue(item.id).albumName, repository.folderName(item.folderId))
        }
    }

    @Test
    fun hideNow_onFolderStep_hidesEveryItemOfSelectedFolders() {
        val vm = viewModel()
        vm.toggleFolder("camera")
        vm.toggleFolder("screenshots")
        vm.hideNow()

        // 9 sample items per folder, each mapped into its bucket-named vault folder.
        assertEquals(18, repository.hidden.size)
        assertEquals(setOf("Camera", "Screenshots"), repository.createdFolderNames.toSet())
        repository.hidden.forEach { item ->
            val name = repository.folderName(item.folderId)
            assertTrue("unexpected folder $name", name == "Camera" || name == "Screenshots")
        }
        assertEquals(emptySet<String>(), vm.state.value.selectedFolderIds)
        assertTrue(vm.state.value.done)
        assertNull(vm.state.value.selectedAlbumId)
    }
}
