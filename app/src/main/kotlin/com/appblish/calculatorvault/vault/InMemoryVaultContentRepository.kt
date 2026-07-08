package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.DefaultVaultFolders
import com.appblish.calculatorvault.vault.model.GridSort
import com.appblish.calculatorvault.vault.model.RecycleBin
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.RestoreSummary
import com.appblish.calculatorvault.vault.model.SortPrefs
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [VaultContentRepository] backing the Phase 2 vault-core screens. It models
 * the full hide → recycle-bin → restore/delete/auto-purge lifecycle over plain state so
 * every flow is exercisable end-to-end without a device or the encrypted file store.
 *
 * The device build swaps this for an implementation that drives [crypto.VaultCrypto]
 * and the app-private file store off the same interface; the ViewModels/screens are
 * unchanged. Seeded with representative sample content so the dashboard, grids, viewers,
 * and recycle bin render populated states out of the box.
 */
class InMemoryVaultContentRepository(
    seed: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) : VaultContentRepository {
    private val mutex = Mutex()
    private var nextId = 0L

    private val itemsState = MutableStateFlow<List<VaultItem>>(emptyList())
    private val foldersState = MutableStateFlow<List<VaultFolder>>(emptyList())
    private val binState = MutableStateFlow<List<RecycleBinEntry>>(emptyList())
    private val prefsState = MutableStateFlow(SortPrefs())

    init {
        if (seed) seedSampleContent()
    }

    override fun items(category: VaultCategory): Flow<List<VaultItem>> =
        itemsState.map { all -> all.filter { it.category == category }.sortedByDescending { it.sortKey } }

    override fun allItems(): Flow<List<VaultItem>> = itemsState.map { all -> all.sortedByDescending { it.sortKey } }

    override fun folders(category: VaultCategory): Flow<List<VaultFolder>> =
        foldersState.map { all -> all.filter { it.category == category && !it.inBin } }

    override fun categoryCounts(): Flow<Map<VaultCategory, Int>> =
        itemsState.map { all ->
            VaultCategory.entries.associateWith { cat -> all.count { it.category == cat } }
        }

    override fun folderCounts(): Flow<Map<VaultCategory, Int>> =
        foldersState.map { all ->
            VaultCategory.entries.associateWith { cat -> all.count { it.category == cat && !it.inBin } }
        }

    override fun recent(limit: Int): Flow<List<VaultItem>> =
        itemsState.map { all -> all.sortedByDescending { it.sortKey }.take(limit) }

    override fun recycleBin(): Flow<List<RecycleBinEntry>> =
        binState.map { entries -> entries.sortedByDescending { it.deletedAt } }

    override suspend fun hide(items: List<VaultItem>): List<VaultItem> =
        mutex.withLock {
            val stored =
                items.map { staged ->
                    // Device impl: encrypt the staged bytes → app-private blob, then delete
                    // the public original. Here we just assign a stable vault id + path.
                    staged.copy(
                        id = "v${nextId++}",
                        encryptedPath = "vault/${staged.category.name.lowercase()}/v$nextId.enc",
                    )
                }
            itemsState.value = itemsState.value + stored
            stored
        }

    override suspend fun createFolder(
        category: VaultCategory,
        name: String,
    ): VaultFolder =
        mutex.withLock {
            val now = clock()
            val folder =
                VaultFolder(id = "f${nextId++}", category = category, name = name, createdAt = now, modifiedAt = now)
            foldersState.value = foldersState.value + folder
            folder
        }

    override suspend fun renameFolder(
        folderId: String,
        name: String,
    ) = mutex.withLock {
        foldersState.value =
            foldersState.value.map {
                if (it.id == folderId) it.copy(name = name, modifiedAt = clock()) else it
            }
    }

    override suspend fun deleteFolderLabels(
        folderIds: Set<String>,
        keepForBinRestore: Boolean,
    ) = mutex.withLock {
        removeLabels(folderIds, keepForBinRestore)
    }

    /**
     * Label bookkeeping shared by [deleteFolderLabels] and the bin-side cleanups. Must run
     * under [mutex]. An album some bin entry still references survives as an
     * [VaultFolder.inBin] tombstone when [keepForBinRestore]; everything else is dropped.
     */
    private fun removeLabels(
        folderIds: Set<String>,
        keepForBinRestore: Boolean,
    ) {
        val referenced = binState.value.mapTo(mutableSetOf()) { it.item.folderId }
        foldersState.value =
            foldersState.value.mapNotNull { folder ->
                when {
                    folder.id !in folderIds -> folder
                    // Tombstones shed their pin — a Bin restore returns albums unpinned (G-2).
                    keepForBinRestore && folder.id in referenced -> folder.copy(inBin = true, pinned = false)
                    else -> null
                }
            }
    }

    /** Drop [VaultFolder.inBin] tombstones no surviving bin entry references. Under [mutex]. */
    private fun pruneOrphanTombstones() {
        val referenced = binState.value.mapTo(mutableSetOf()) { it.item.folderId }
        foldersState.value = foldersState.value.filterNot { it.inBin && it.id !in referenced }
    }

    override suspend fun moveToFolder(
        itemIds: Set<String>,
        folderId: String?,
    ) = mutex.withLock {
        itemsState.value =
            itemsState.value.map { if (it.id in itemIds) it.copy(folderId = folderId) else it }
        reconcileCovers()
    }

    // --- Organization polish (W3-E): mirrors the device repository's index semantics ---

    override fun sortPrefs(): Flow<SortPrefs> = prefsState

    override suspend fun setPhotoSort(sort: GridSort) =
        mutex.withLock { prefsState.value = prefsState.value.copy(photoSort = sort) }

    override suspend fun setAlbumSort(sort: GridSort) =
        mutex.withLock { prefsState.value = prefsState.value.copy(albumSort = sort) }

    override suspend fun setAlbumPhotoSortOverride(
        folderId: String,
        sort: GridSort?,
    ) = mutex.withLock {
        foldersState.value =
            foldersState.value.map { if (it.id == folderId) it.copy(photoSortOverride = sort) else it }
    }

    override suspend fun setFolderPinned(
        folderId: String,
        pinned: Boolean,
    ) = mutex.withLock {
        foldersState.value =
            foldersState.value.map { if (it.id == folderId) it.copy(pinned = pinned) else it }
    }

    override suspend fun setFolderCover(
        folderId: String,
        itemId: String?,
    ) = mutex.withLock {
        val valid = itemId == null || itemsState.value.any { it.id == itemId && it.folderId == folderId }
        if (valid) {
            foldersState.value =
                foldersState.value.map { if (it.id == folderId) it.copy(coverItemId = itemId) else it }
        }
    }

    override suspend fun setRotation(
        itemId: String,
        degrees: Int,
    ): Boolean =
        mutex.withLock {
            val net = ((degrees % 360) + 360) % 360
            if (itemsState.value.none { it.id == itemId }) return@withLock false
            itemsState.value =
                itemsState.value.map { if (it.id == itemId) it.copy(rotationDegrees = net) else it }
            true
        }

    /**
     * Design G-5: a cover pointer whose item left the album is dropped the moment it
     * dangles, so a later restore never re-promotes it. Must run under [mutex].
     */
    private fun reconcileCovers() {
        val liveFolderById = itemsState.value.associateBy({ it.id }, { it.folderId })
        foldersState.value =
            foldersState.value.map { folder ->
                val cover = folder.coverItemId
                if (cover != null && liveFolderById[cover] != folder.id) folder.copy(coverItemId = null) else folder
            }
    }

    override suspend fun unhide(itemIds: Set<String>): Int =
        mutex.withLock {
            // No real device write-back off-device: drop the un-hidden items from the vault
            // list (the device impl publishes the decrypted bytes to public storage first).
            val (restored, kept) = itemsState.value.partition { it.id in itemIds }
            itemsState.value = kept
            reconcileCovers()
            restored.size
        }

    override suspend fun unhideDetailed(itemIds: Set<String>): RestoreSummary {
        // Off-device there is no public storage to miss or collide with: every known item
        // "restores" to its original spot; unknown ids surface as failed (left in place),
        // mirroring the device repository's arithmetic.
        val restored = unhide(itemIds)
        return RestoreSummary(restoredToOriginal = restored, failed = itemIds.size - restored)
    }

    override suspend fun moveToRecycleBin(itemIds: Set<String>) =
        mutex.withLock {
            val (moved, kept) = itemsState.value.partition { it.id in itemIds }
            // deletedAt uses the item's own recency as a deterministic clock stand-in;
            // the device impl stamps System.currentTimeMillis().
            binState.value = binState.value + moved.map { RecycleBinEntry(it, deletedAt = it.sortKey) }
            itemsState.value = kept
            reconcileCovers()
        }

    override suspend fun restore(itemIds: Set<String>): Int =
        mutex.withLock {
            val (restored, kept) = binState.value.partition { it.item.id in itemIds }
            // A restored item whose album went to the bin with it resurrects the album
            // (tombstone flips live — design F-3, the album comes back whole); an album
            // deleted outright while the item sat in the bin falls back to the root.
            val liveIds = foldersState.value.filterNot { it.inBin }.mapTo(mutableSetOf()) { it.id }
            val tombstoneIds = foldersState.value.filter { it.inBin }.mapTo(mutableSetOf()) { it.id }
            val resurrect = restored.mapNotNullTo(mutableSetOf()) {
                it.item.folderId?.takeIf { id ->
                    id in tombstoneIds
                }
            }
            foldersState.value =
                foldersState.value.map { if (it.id in resurrect) it.copy(inBin = false) else it }
            itemsState.value = itemsState.value +
                restored.map { entry ->
                    val folderId = entry.item.folderId
                    if (folderId != null && folderId !in liveIds && folderId !in resurrect) {
                        entry.item.copy(folderId = null)
                    } else {
                        entry.item
                    }
                }
            binState.value = kept
            pruneOrphanTombstones()
            restored.size
        }

    override suspend fun deleteForever(itemIds: Set<String>): Int =
        mutex.withLock {
            val (removed, kept) = binState.value.partition { it.item.id in itemIds }
            binState.value = kept
            pruneOrphanTombstones()
            removed.size
        }

    override suspend fun permanentlyDelete(itemIds: Set<String>): Int =
        mutex.withLock {
            // Straight vault removal (the device impl also securely wipes each blob); the
            // demo store holds no blob, so dropping the index entry is the whole delete.
            val (removed, kept) = itemsState.value.partition { it.id in itemIds }
            itemsState.value = kept
            reconcileCovers()
            removed.size
        }

    override suspend fun purgeExpired(now: Long): Int =
        mutex.withLock {
            val (expired, kept) = RecycleBin.partitionExpired(binState.value, now)
            binState.value = kept
            pruneOrphanTombstones()
            expired.size
        }

    override suspend fun openDecrypted(itemId: String): ByteArray? {
        // Demo store holds no bytes; the device impl streams the blob through VaultCrypto.
        return itemsState.value.firstOrNull { it.id == itemId }?.let { ByteArray(0) }
    }

    private fun seedSampleContent() {
        // Predefined default folders (xlock / Figma parity, APP-206) — mirrors what the
        // device repository seeds into a fresh vault's encrypted index on first init.
        foldersState.value = DefaultVaultFolders.forFreshVault()

        val day = 24L * 60L * 60L * 1000L
        val base = 100L * day // fixed epoch base so sample dates are deterministic

        fun item(
            cat: VaultCategory,
            name: String,
            dateLabel: String,
            ageDays: Long,
        ) = VaultItem(
            id = "v${nextId++}",
            category = cat,
            originalName = name,
            dateLabel = dateLabel,
            sortKey = base - ageDays * day,
            encryptedPath = "vault/${cat.name.lowercase()}/v$nextId.enc",
        )
        itemsState.value =
            listOf(
                item(VaultCategory.PHOTOS, "IMG_2041.jpg", "Today", 0),
                item(VaultCategory.PHOTOS, "IMG_2038.jpg", "Today", 0),
                item(VaultCategory.PHOTOS, "IMG_1990.jpg", "Yesterday", 1),
                item(VaultCategory.PHOTOS, "beach.png", "12 Jun 2026", 8),
                item(VaultCategory.VIDEOS, "clip_0007.mp4", "Yesterday", 1),
                item(VaultCategory.VIDEOS, "birthday.mp4", "10 Jun 2026", 10),
                item(VaultCategory.AUDIOS, "voice_memo.m4a", "Today", 0),
                item(VaultCategory.FILES, "payslip_june.pdf", "3 Jun 2026", 17),
                item(VaultCategory.FILES, "passport.pdf", "1 May 2026", 50),
                item(VaultCategory.CONTACTS, "Alex Rivera", "Today", 0),
            )
    }
}
