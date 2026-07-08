package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.RestoreSummary
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.UnhideDisposition
import com.appblish.calculatorvault.vault.model.UnhideOutcome
import com.appblish.calculatorvault.vault.model.UnhideResult
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.io.File

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
    fun folderCounts(): Flow<Map<VaultCategory, Int>> = flowOf(VaultCategory.entries.associateWith { 0 })

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

    // --- Album-level actions (W2-E, spec §3). An "album" is a [VaultFolder]: nothing but
    // an encrypted-index label plus the items pointing at it, so every operation below is
    // index arithmetic over the photo-level primitives — no blob is renamed or decrypted.

    /**
     * Rename the album [folderId] — a **label write in the encrypted index only** (spec
     * §1.2/§3.1): no blob is renamed (blobs stay UUIDs) and no readable public folder is
     * ever created. Stamps the album's modified time. Unknown ids are a no-op. Default
     * no-op so minimal fakes keep compiling.
     */
    suspend fun renameFolder(
        folderId: String,
        name: String,
    ) {}

    /**
     * Remove the album labels [folderIds] from every album surface. With
     * [keepForBinRestore] a non-empty-of-bin-entries album is retained as an
     * [VaultFolder.inBin] tombstone so a Recycle-Bin restore can resurrect the album whole
     * (design F-3); otherwise (and for albums nothing in the bin references) the record is
     * dropped for good. Label bookkeeping only — contents are the caller's business.
     * Default no-op so minimal fakes keep compiling.
     */
    suspend fun deleteFolderLabels(
        folderIds: Set<String>,
        keepForBinRestore: Boolean = false,
    ) {}

    /**
     * Merge the contents of [sourceFolderIds] into [targetFolderId] (spec §3.2): every
     * member item's index entry relocates to the target, then each fully-emptied source
     * label is removed. An index-entry relocation only — nothing is decrypted, no blob
     * moves. The target is never a source (filtered here as a guard; the UI also disables
     * it). Returns how many items moved, for the "N moved" summary.
     */
    suspend fun mergeFolders(
        sourceFolderIds: Set<String>,
        targetFolderId: String,
    ): Int {
        val sources = sourceFolderIds - targetFolderId
        if (sources.isEmpty()) return 0
        val memberIds =
            allItems()
                .first()
                .filter { it.folderId in sources }
                .mapTo(mutableSetOf()) { it.id }
        moveToFolder(memberIds, targetFolderId)
        // moveToFolder is an atomic index rewrite, so the sources are now empty; a source
        // whose members sit in the recycle bin keeps its tombstone path via the flag.
        deleteFolderLabels(sources, keepForBinRestore = false)
        return memberIds.size
    }

    /**
     * Un-hide whole albums (spec §3.3): every photo in [folderIds] decrypts out to
     * [destination] under the photo-level rules — per-file fallback to Downloads, never
     * silent, blob kept on failure — then each fully-emptied album label is removed. On a
     * partial failure the album **remains, holding exactly the failed photos** (design §6);
     * only an album whose every member left the vault loses its label.
     */
    suspend fun unhideFolders(
        folderIds: Set<String>,
        destination: UnhideDestination,
    ): UnhideResult {
        val memberIds =
            allItems()
                .first()
                .filter { it.folderId in folderIds }
                .mapTo(mutableSetOf()) { it.id }
        val result = unhideTo(memberIds, destination)
        val stillHeld =
            allItems()
                .first()
                .mapTo(mutableSetOf()) { it.folderId }
        deleteFolderLabels(folderIds.filterNot { it in stillHeld }.toSet(), keepForBinRestore = false)
        return result
    }

    /**
     * Album delete → Recycle Bin (spec §3.4, soft path): contents move to the bin still
     * encrypted **keeping their album grouping**, and the emptied labels become
     * [VaultFolder.inBin] tombstones so a bin restore brings the album back whole (design
     * F-3). Returns how many items were binned (0 for empty albums, whose labels are
     * simply removed).
     */
    suspend fun moveFoldersToRecycleBin(folderIds: Set<String>): Int {
        val memberIds =
            allItems()
                .first()
                .filter { it.folderId in folderIds }
                .mapTo(mutableSetOf()) { it.id }
        moveToRecycleBin(memberIds)
        deleteFolderLabels(folderIds, keepForBinRestore = true)
        return memberIds.size
    }

    /**
     * Album delete → Permanent (spec §3.4, hard path, behind the 2-step confirm): secure
     * blob wipe + index-entry removal for every photo in [folderIds], then the labels are
     * dropped for good. Returns how many items were destroyed for the "X erased" summary.
     */
    suspend fun permanentlyDeleteFolders(folderIds: Set<String>): Int {
        val memberIds =
            allItems()
                .first()
                .filter { it.folderId in folderIds }
                .mapTo(mutableSetOf()) { it.id }
        val destroyed = permanentlyDelete(memberIds)
        deleteFolderLabels(folderIds, keepForBinRestore = false)
        return destroyed
    }

    /**
     * Un-hide (restore to public storage) the vault items [itemIds]: decrypt each blob,
     * write the bytes back to the original public location so it reappears in the system
     * gallery, then remove the item (index + blob) from the vault. Returns the number of
     * items successfully un-hidden; an item whose write-back fails is left untouched in
     * the vault (its only copy is never lost). This is the board's "watch it return to
     * the gallery" beat — the inverse of [hide].
     */
    suspend fun unhide(itemIds: Set<String>): Int = unhideTo(itemIds, UnhideDestination.Original).unhidden

    /**
     * Un-hide [itemIds] to [destination] and report, per item, whether it landed at the
     * requested destination, fell back to Downloads (original/chosen dir unavailable), or
     * failed entirely (blob kept in the vault — spec §1.4, never lose the only copy). This
     * is the result-bearing form the Unhide dialog uses so it can show the honest §7
     * snackbar ("Unhid N", "saved M to {dest}"). Default impl delegates to [unhide] so
     * preview/in-memory fakes need not model destinations, reporting every success as
     * [UnhideDisposition.REQUESTED].
     */
    suspend fun unhideTo(
        itemIds: Set<String>,
        destination: UnhideDestination,
    ): UnhideResult {
        val ids = itemIds.toList()
        val done = unhide(itemIds)
        return UnhideResult(
            ids.take(done).map { UnhideOutcome(it, UnhideDisposition.REQUESTED) } +
                ids.drop(done).map { UnhideOutcome(it, UnhideDisposition.FAILED) },
        )
    }

    /**
     * Permanently destroy the *vault* items [itemIds]: securely wipe each encrypted blob
     * (overwrite before unlink) and remove its index entry — the 2-step-confirmed
     * "Delete permanently" path (spec §1.5). Distinct from [deleteForever], which operates
     * on items already sitting in the recycle bin. Returns how many items were actually
     * destroyed so bulk summaries can report honestly (W1-E3). Default impl removes the
     * index entries; the device repository adds the secure blob wipe + foreground service.
     */
    suspend fun permanentlyDelete(itemIds: Set<String>): Int {
        // In-memory fakes hold no blob; dropping the index entry is a full delete for them.
        moveToRecycleBin(itemIds)
        return deleteForever(itemIds)
    }

    /**
     * [unhide] with the full per-operation outcome (spec §8 / design call D-3): how many
     * items returned to their original location, how many fell back to the visible
     * Downloads folder (§7 fallback — and where), and how many could not be written at all
     * (left in the vault). Screens use this to surface the mandatory restore-fallback
     * notice. Built from [unhideTo]'s per-item dispositions, so any implementation that
     * reports destinations honestly gets honest summaries for free; fakes that only
     * override [unhide] report every success as restored-to-original via the [unhideTo]
     * default.
     */
    suspend fun unhideDetailed(itemIds: Set<String>): RestoreSummary {
        val result = unhideTo(itemIds, UnhideDestination.Original)
        return RestoreSummary(
            restoredToOriginal = result.requested,
            restoredToFallback = result.fellBack,
            fallbackDestination = result.fallbackDestination,
            failed = result.failed,
        )
    }

    /** Send [itemIds] to the recycle bin (recoverable). */
    suspend fun moveToRecycleBin(itemIds: Set<String>)

    /**
     * Restore recycle-bin entries [itemIds] back to their album (the index entry returns
     * with its folder intact; the still-encrypted blob never moved). Returns how many
     * entries actually restored so bulk summaries can report honestly (W1-E4, spec §1.6):
     * an id that no longer resolves to a bin entry — or whose blob has gone missing —
     * stays in the bin and is *not* counted.
     */
    suspend fun restore(itemIds: Set<String>): Int

    /**
     * Permanently destroy recycle-bin entries [itemIds] (irreversible): secure blob wipe
     * (overwrite before unlink) + index-entry removal on the device implementation.
     * Returns how many entries were actually destroyed (ids not present in the bin are
     * not counted) for the W1-E4 "X done, Y failed" summary.
     */
    suspend fun deleteForever(itemIds: Set<String>): Int

    /**
     * Purge recycle-bin entries past the auto-delete window, using [now] as the clock.
     * Returns the number purged. Call on vault open / recycle-bin open.
     */
    suspend fun purgeExpired(now: Long): Int

    /** Read a decrypted blob for a viewer. Null if the item or blob is missing. */
    suspend fun openDecrypted(itemId: String): ByteArray?

    /**
     * Decrypt [itemId]'s blob straight into [dest] — the large-media seam for viewers that
     * must not hold a whole video in memory (bulk-op hardening, spec §11). The device
     * implementation streams blob → cipher → file and returns false (leaving no partial
     * [dest]) when the item/blob/key is missing or the GCM tag fails. This default
     * materializes [openDecrypted] so in-memory fakes work unchanged.
     */
    suspend fun decryptToFile(
        itemId: String,
        dest: File,
    ): Boolean {
        val bytes = openDecrypted(itemId) ?: return false
        dest.outputStream().use { it.write(bytes) }
        return true
    }

    /**
     * Read [itemId]'s stored grid thumbnail as decrypted JPEG bytes (APP-244 encrypted
     * on-disk thumb cache) — a few tens of KB instead of the full blob. Null when no thumb
     * has been generated yet (pre-APP-244 items, non-visual categories) or the vault is
     * locked; callers then backfill via [saveThumbnail]. Default null so in-memory fakes
     * keep working (the pipeline falls back to the full-blob decode path).
     */
    suspend fun openThumbnail(itemId: String): ByteArray? = null

    /**
     * Persist [jpegBytes] as [itemId]'s stored grid thumbnail, **encrypted** — plaintext
     * previews must never touch disk (APP-244 board mandate). Called at hide-time by the
     * device implementation itself and by the pipeline's lazy backfill for items hidden
     * before thumbs existed. Default no-op for fakes.
     */
    suspend fun saveThumbnail(
        itemId: String,
        jpegBytes: ByteArray,
    ) {}
}
