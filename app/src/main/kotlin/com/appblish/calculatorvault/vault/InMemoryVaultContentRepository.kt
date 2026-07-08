package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.DefaultVaultFolders
import com.appblish.calculatorvault.vault.model.RecycleBin
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.RestoreSummary
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
) : VaultContentRepository {
    private val mutex = Mutex()
    private var nextId = 0L

    private val itemsState = MutableStateFlow<List<VaultItem>>(emptyList())
    private val foldersState = MutableStateFlow<List<VaultFolder>>(emptyList())
    private val binState = MutableStateFlow<List<RecycleBinEntry>>(emptyList())

    init {
        if (seed) seedSampleContent()
    }

    override fun items(category: VaultCategory): Flow<List<VaultItem>> =
        itemsState.map { all -> all.filter { it.category == category }.sortedByDescending { it.sortKey } }

    override fun allItems(): Flow<List<VaultItem>> = itemsState.map { all -> all.sortedByDescending { it.sortKey } }

    override fun folders(category: VaultCategory): Flow<List<VaultFolder>> =
        foldersState.map { all -> all.filter { it.category == category } }

    override fun categoryCounts(): Flow<Map<VaultCategory, Int>> =
        itemsState.map { all ->
            VaultCategory.entries.associateWith { cat -> all.count { it.category == cat } }
        }

    override fun folderCounts(): Flow<Map<VaultCategory, Int>> =
        foldersState.map { all ->
            VaultCategory.entries.associateWith { cat -> all.count { it.category == cat } }
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
            val folder = VaultFolder(id = "f${nextId++}", category = category, name = name)
            foldersState.value = foldersState.value + folder
            folder
        }

    override suspend fun moveToFolder(
        itemIds: Set<String>,
        folderId: String?,
    ) = mutex.withLock {
        itemsState.value =
            itemsState.value.map { if (it.id in itemIds) it.copy(folderId = folderId) else it }
    }

    override suspend fun unhide(itemIds: Set<String>): Int =
        mutex.withLock {
            // No real device write-back off-device: drop the un-hidden items from the vault
            // list (the device impl publishes the decrypted bytes to public storage first).
            val (restored, kept) = itemsState.value.partition { it.id in itemIds }
            itemsState.value = kept
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
        }

    override suspend fun restore(itemIds: Set<String>): Int =
        mutex.withLock {
            val (restored, kept) = binState.value.partition { it.item.id in itemIds }
            itemsState.value = itemsState.value + restored.map { it.item }
            binState.value = kept
            restored.size
        }

    override suspend fun deleteForever(itemIds: Set<String>): Int =
        mutex.withLock {
            val (removed, kept) = binState.value.partition { it.item.id in itemIds }
            binState.value = kept
            removed.size
        }

    override suspend fun permanentlyDelete(itemIds: Set<String>): Int =
        mutex.withLock {
            // Straight vault removal (the device impl also securely wipes each blob); the
            // demo store holds no blob, so dropping the index entry is the whole delete.
            val (removed, kept) = itemsState.value.partition { it.id in itemIds }
            itemsState.value = kept
            removed.size
        }

    override suspend fun purgeExpired(now: Long): Int =
        mutex.withLock {
            val (expired, kept) = RecycleBin.partitionExpired(binState.value, now)
            binState.value = kept
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
