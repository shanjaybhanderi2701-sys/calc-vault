package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Read/write boundary for hidden vault *content* (media, files, folders, recycle bin) —
 * kept separate from [VaultRepository], which owns only the secret-code gate. Screens
 * observe the [Flow]s and issue suspend commands; the concrete implementation performs
 * the encrypt-and-remove-from-public-storage pipeline.
 *
 * The hide/import contract: [hide] takes items already staged from a public source
 * (MediaStore / SAF), encrypts each into the vault, deletes the public original, and
 * emits the new [VaultItem]s. Deleting from the vault first routes through the recycle
 * bin ([moveToRecycleBin]); only [deleteForever]/auto-purge destroys the blob.
 */
interface VaultContentRepository {
    /**
     * Derive the data key from the session passphrase ([VaultSession]) and load the
     * encrypted index from the public `.CalcVault/` folder, making content readable. A
     * no-op until All Files Access is granted and a passphrase is set, so it is safe to call
     * eagerly on vault entry and again once the storage primer grants access. Default no-op
     * for in-memory/preview implementations that hold no on-disk key.
     */
    fun unlock() {}

    /**
     * Drop the current session's data key and in-memory content, returning the vault to a
     * locked, empty state. Called when leaving the vault and, critically, before opening a
     * *different* vault (real ↔ decoy) so one session's content can never leak into another:
     * the shared singleton repository is re-keyed from the new [VaultSession] passphrase on
     * the next [unlock]. Default no-op for in-memory/preview implementations.
     */
    fun lock() {}

    /** All hidden items in [category], newest first (drives the date-grouped grid). */
    fun items(category: VaultCategory): Flow<List<VaultItem>>

    /** Every hidden item across all categories, newest first (viewer lookup by id). */
    fun allItems(): Flow<List<VaultItem>>

    /** Folders within [category] (Create Folder / folder slideshow). */
    fun folders(category: VaultCategory): Flow<List<VaultFolder>>

    /** Per-category counts for the home dashboard tiles. */
    fun categoryCounts(): Flow<Map<VaultCategory, Int>>

    /**
     * Per-category folder counts for the home dashboard's dual-count tile subtitles
     * ("300 Photos / 8 Folders"). Defaults to zeros so preview/test fakes need not
     * implement it; the real repositories derive it from their folder index.
     */
    fun folderCounts(): Flow<Map<VaultCategory, Int>> =
        flowOf(VaultCategory.entries.associateWith { 0 })

    /** The most recently hidden items across all categories (home "Recent" strip). */
    fun recent(limit: Int = 12): Flow<List<VaultItem>>

    /** Everything currently in the recycle bin, newest deletion first. */
    fun recycleBin(): Flow<List<RecycleBinEntry>>

    /** Encrypt and hide [items], removing each public original. Returns the stored items. */
    suspend fun hide(items: List<VaultItem>): List<VaultItem>

    /** Create a folder in [category]; returns it. */
    suspend fun createFolder(
        category: VaultCategory,
        name: String,
    ): VaultFolder

    /** Move [itemIds] into [folderId] (null = category root). */
    suspend fun moveToFolder(
        itemIds: Set<String>,
        folderId: String?,
    )

    /**
     * Un-hide (restore to public storage) the vault items [itemIds]: decrypt each blob,
     * write the bytes back to the original public location so it reappears in the system
     * gallery, then remove the item (index + blob) from the vault. Returns the number of
     * items successfully un-hidden; an item whose write-back fails is left untouched in
     * the vault (its only copy is never lost). This is the board's "watch it return to
     * the gallery" beat — the inverse of [hide].
     */
    suspend fun unhide(itemIds: Set<String>): Int

    /** Send [itemIds] to the recycle bin (recoverable). */
    suspend fun moveToRecycleBin(itemIds: Set<String>)

    /** Restore recycle-bin entries [itemIds] back to their category. */
    suspend fun restore(itemIds: Set<String>)

    /** Permanently destroy recycle-bin entries [itemIds] (irreversible). */
    suspend fun deleteForever(itemIds: Set<String>)

    /**
     * Purge recycle-bin entries past the auto-delete window, using [now] as the clock.
     * Returns the number purged. Call on vault open / recycle-bin open.
     */
    suspend fun purgeExpired(now: Long): Int

    /** Read a decrypted blob for a viewer. Null if the item or blob is missing. */
    suspend fun openDecrypted(itemId: String): ByteArray?
}
