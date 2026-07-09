package com.appblish.calculatorvault.vault.model

/**
 * The five media-vault categories from the deck's home dashboard. Each carries the
 * display label and a stable accent hue (ARGB) for its icon-chip — the single green
 * brand accent is reserved for CTAs, so category chips use their own muted hues.
 */
enum class VaultCategory(
    val label: String,
    val chipColor: Long,
) {
    PHOTOS("Photos", 0xFF3B82F6),
    VIDEOS("Videos", 0xFFA855F7),
    AUDIOS("Audios", 0xFFF59E0B),
    FILES("Files", 0xFF14B8A6),
    CONTACTS("Contacts", 0xFFEC4899),
    ;

    companion object {
        /**
         * The Phase-1 media vault scope (build spec §0/§3, APP-225): Photos, Videos, Audio
         * only. FILES and CONTACTS stay in the enum so persisted indexes keep decoding, but
         * no Phase-1 surface offers them.
         */
        val PHASE1: List<VaultCategory> = listOf(PHOTOS, VIDEOS, AUDIOS)
    }
}

/**
 * A single item hidden inside the vault. [sortKey] is a recency key (epoch millis) used
 * for date grouping and "Recent"; [dateLabel] is the pre-formatted section header
 * ("Today", "12 Jun 2026"). [folderId] is null for items at a category's root.
 *
 * [encryptedPath] points at the app-private, AES-encrypted blob; [originalName] is the
 * public-storage name shown in viewers. The original public copy is deleted at import
 * time (see the hide/import pipeline), so this record is the only reference.
 */
data class VaultItem(
    val id: String,
    val category: VaultCategory,
    val originalName: String,
    val dateLabel: String,
    val sortKey: Long,
    val folderId: String? = null,
    val encryptedPath: String? = null,
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    // Import-only: the public-storage content Uri of the original, carried from the
    // hide/import picker to the repository so it can stream the real bytes through
    // VaultCrypto and then remove the public copy. Never persisted on a stored item.
    val sourceUri: String? = null,
    // MIME type of the original (e.g. "image/jpeg", "video/mp4"), used by viewers to
    // decode the decrypted blob. Null for legacy/sample items.
    val mimeType: String? = null,
    // Public-storage RELATIVE_PATH the original lived in at hide time (e.g. "DCIM/Camera/"),
    // captured from MediaStore so un-hide can write the decrypted bytes back to the same
    // gallery album. Persisted. Null when unknown → un-hide falls back to Downloads and
    // reports it (see MediaSink.writeBack + [UnhideDisposition]).
    val relativePath: String? = null,
    // Pixel dimensions of the original, captured at hide time from the source bounds so the
    // Property dialog can report "W × H" without ever decrypting the blob. 0 == unknown
    // (older items / non-image categories) → the Property dialog shows a muted "—".
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    // Original last-modified time (epoch millis) read from MediaStore at hide time. Distinct
    // from [sortKey], which is the added-to-vault time. 0 == unknown → Property shows "—".
    val dateModifiedMs: Long = 0L,
    // Capture time (MediaStore DATE_TAKEN, epoch millis) read at hide time — the "Date
    // taken" sort key (W3-E, spec §4.1). 0 == unknown → that item sorts by its
    // Last-modified value instead (no "Unknown" bucket, design W3-D §7).
    val dateTakenMs: Long = 0L,
    // Persisted net display orientation in clockwise degrees (0/90/180/270) — the W3-E
    // rotate-persist bit (spec §2.2). Orientation metadata in the encrypted index only;
    // the blob bytes are never re-encoded. Viewers add it to the decoded bitmap and the
    // stored thumbnail is re-derived on commit, so every cached-thumbnail consumer agrees.
    val rotationDegrees: Int = 0,
)

/**
 * Where an un-hide writes the decrypted bytes. [Original] targets the album the file was
 * hidden from (from the encrypted index); [Chosen] targets a caller-picked public
 * RELATIVE_PATH. Either way, if the primary destination is missing/unwritable the
 * repository falls back to Downloads and reports it — spec §1.4: never fail silently.
 */
sealed interface UnhideDestination {
    data object Original : UnhideDestination

    /**
     * A user-picked destination folder (APP-293 P0-2 / APP-299 P0-2). [treeUri] is the SAF
     * tree the user actually picked and granted — the **primary** write route for a chosen
     * folder, because writing straight into that tree is the only way to reliably land in
     * the picked folder on a real device (MediaStore rejects/normalizes non-standard chosen
     * roots). [relativePath] is the reconstructed primary-volume MediaStore RELATIVE_PATH
     * when the tree parses to one; it is now a gallery-indexed *fallback* used only if the
     * SAF-tree write fails. [label] names the folder in the result copy.
     */
    data class Chosen(
        val relativePath: String? = null,
        val treeUri: String? = null,
        val label: String? = null,
    ) : UnhideDestination
}

/** How a single item actually landed when un-hidden (drives the honest result snackbar). */
enum class UnhideDisposition {
    /** Written to the requested destination (original album or chosen folder). */
    REQUESTED,

    /** Requested destination was unavailable; saved to the fallback (Downloads) instead. */
    FALLBACK,

    /** Nothing could be written; the encrypted vault copy was kept (never lost). */
    FAILED,
}

/** Per-item un-hide outcome: what happened and, for a fallback, where it actually landed. */
data class UnhideOutcome(
    val itemId: String,
    val disposition: UnhideDisposition,
    val destinationLabel: String? = null,
)

/**
 * Aggregate result of an un-hide over one or more items. Surfaces the counts the design's
 * §7 snackbar needs — "Unhid N", "saved M to {dest}", total-failure — without the UI
 * having to re-derive them.
 */
data class UnhideResult(
    val outcomes: List<UnhideOutcome> = emptyList(),
) {
    val requested: Int get() = outcomes.count { it.disposition == UnhideDisposition.REQUESTED }
    val fellBack: Int get() = outcomes.count { it.disposition == UnhideDisposition.FALLBACK }
    val failed: Int get() = outcomes.count { it.disposition == UnhideDisposition.FAILED }

    /** Items that left the vault (landed somewhere), whether at the requested dest or fallback. */
    val unhidden: Int get() = requested + fellBack

    /** True when nothing at all could be written — the one case that warrants a modal, not a snackbar. */
    val totalFailure: Boolean get() = outcomes.isNotEmpty() && unhidden == 0

    /** The fallback destination label, if any item fell back (all fallbacks share one dir). */
    val fallbackDestination: String?
        get() = outcomes.firstOrNull { it.disposition == UnhideDisposition.FALLBACK }?.destinationLabel
}

/**
 * A user-created album within a category (Create Album from the FAB menu; container
 * terminology is "Album" everywhere per APP-218 — the type keeps its historical name so
 * persisted indexes keep decoding). The album is nothing but this index record: its name
 * is an encrypted-index label, never a filesystem folder, and its contents are the items
 * whose [VaultItem.folderId] points here.
 *
 * [createdAt]/[modifiedAt] back the Album property dialog (W2-E §8): added-to-vault time
 * of the label and the latest label change (create/rename). 0 == unknown (legacy index) →
 * the dialog shows "—". Content-level "modified" is computed from the items themselves.
 *
 * [inBin] marks an album whose delete-to-Bin ran (W2-E §7, design F-3): the label is
 * hidden from every album surface but retained so a Recycle-Bin restore can bring the
 * album back whole instead of dumping loose photos. The record is dropped for good once
 * no bin entry references it.
 */
data class VaultFolder(
    val id: String,
    val category: VaultCategory,
    val name: String,
    val itemCount: Int = 0,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val inBin: Boolean = false,
    // W3-E pin bit (spec §3.6): pinned albums cluster above unpinned on the home album
    // grid; the active sort applies within each cluster and pin-time is never an ordering
    // key (design G-1). One bit in the encrypted index — nothing moves on disk. A Bin
    // restore brings an album back UNpinned (design G-2), so the tombstone path drops it.
    val pinned: Boolean = false,
    // W3-E cover pointer (spec §2.7/§3.7): the member item chosen as this album's cover
    // tile. Null → fall back to the newest member by added-to-vault time (design G-5).
    // Cleared the moment the pointed-at item leaves the album (moved/unhidden/deleted/
    // binned), so a later Bin restore never silently re-promotes it.
    val coverItemId: String? = null,
    // W3-E per-album photo-sort override (spec §4.1's "apply to this folder only",
    // design G-8): non-null replaces the vault-wide photo sort inside this album.
    val photoSortOverride: GridSort? = null,
)

/**
 * The predefined folders seeded into every vault. Build spec §4 (APP-225, board-ruled on
 * APP-220): each of the three Phase-1 categories — Photos, Videos, Audio — is created on
 * first use, even when empty, containing exactly one default empty **"Download"** folder.
 * Seeding is per vault *namespace*: the real vault and each decoy slot (`decoy_<slot>/`)
 * seed into their own encrypted index, so a decoy's folders can never leak into the real
 * vault.
 *
 * Because the public `.CalcVault/` index survives uninstall **by design**, seeding cannot be
 * first-run-only: an index written by an older build (pre-"Download" catalog, or with the
 * legacy Camera/Screenshots/… set) would otherwise never receive the required default. So
 * seeding is **idempotent and category-scoped** ([missingDefaults]): on every index load,
 * any Phase-1 category holding ZERO folders gets its catalog default(s); a category with
 * ≥1 folder — user-created, legacy seed, or a surviving "Download" — is left alone, so a
 * default the user deleted stays deleted while they keep other folders in that category.
 * Tradeoff: deleting a category's *last* folder brings "Download" back on the next unlock;
 * accepted, to guarantee migrated/stale vaults always open with a usable default.
 *
 * Each default folder carries a **stable, derived id** (`seed_<category>_<slug>`) so seeding
 * is idempotent and a folder the user later renames is never resurrected.
 */
object DefaultVaultFolders {
    /** (category, display name) pairs seeded into a fresh vault, in display order. */
    private val CATALOG: List<Pair<VaultCategory, String>> =
        VaultCategory.PHASE1.map { it to "Download" }

    /** The default folders for a brand-new vault namespace, with stable ids. */
    fun forFreshVault(): List<VaultFolder> = missingDefaults(existing = emptyList())

    /**
     * The catalog defaults missing from [existing]: for each Phase-1 category with ZERO
     * folders in [existing], that category's default folder(s). Categories that already hold
     * ≥1 folder contribute nothing (see the object KDoc for the deleted-vs-migrated
     * tradeoff); non-Phase-1 folders in [existing] neither seed nor suppress anything.
     */
    fun missingDefaults(existing: List<VaultFolder>): List<VaultFolder> {
        val populated = existing.mapTo(mutableSetOf()) { it.category }
        return CATALOG
            .filter { (category, _) -> category !in populated }
            .map { (category, name) -> VaultFolder(id = seedId(category, name), category = category, name = name) }
    }

    /** Stable id for a seeded folder — deterministic so re-seeding never duplicates it. */
    private fun seedId(
        category: VaultCategory,
        name: String,
    ): String = "seed_${category.name.lowercase()}_${name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
}

/**
 * The user-facing outcome of a restore (un-hide) operation — spec §8 + design call D-3 on
 * APP-224. Restore never fails silently: every item either returned to its original public
 * location, landed in a visible fallback folder ("DCIM/Restored", "Music/Restored"…)
 * because the original path was missing/unwritable/name-collided, or stayed safely in the
 * vault ([failed] — nothing writable at all). The category/viewer screens turn this into
 * the single per-operation snackbar.
 */
data class RestoreSummary(
    val restoredToOriginal: Int = 0,
    val restoredToFallback: Int = 0,
    /** Visible fallback folder (e.g. "DCIM/Restored") when [restoredToFallback] > 0. */
    val fallbackDestination: String? = null,
    val failed: Int = 0,
) {
    val restored: Int get() = restoredToOriginal + restoredToFallback

    /**
     * The single per-operation user notice (design call D-3 copy, verbatim rules): one
     * summary per bulk op, never one toast per item; never silent when anything fell back
     * or failed. Null only when nothing was attempted.
     */
    fun noticeText(): String? {
        val destination = fallbackDestination ?: "Restored"
        return when {
            restored == 0 && failed == 0 -> null
            failed > 0 && restored == 0 -> "Couldn't restore — check storage access"
            restoredToFallback > 0 && restoredToOriginal > 0 ->
                "Restored $restoredToOriginal items · $restoredToFallback saved to $destination (original folder unavailable)"
            restoredToFallback > 0 -> "Original folder unavailable — restored to $destination"
            else -> if (restored == 1) "Restored 1 item." else "Restored $restored items."
        }
    }
}

/**
 * An item currently in the recycle bin. [deletedAt] starts the auto-delete countdown;
 * once it exceeds [RecycleBin.AUTO_DELETE_WINDOW_DAYS] the entry is purged for good.
 */
data class RecycleBinEntry(
    val item: VaultItem,
    val deletedAt: Long,
)

/**
 * Pure recycle-bin policy — no Android/Compose deps so the auto-delete window is
 * unit-testable. The bin auto-purges entries older than [AUTO_DELETE_WINDOW_DAYS];
 * restore/delete are user actions handled by the repository.
 */
object RecycleBin {
    const val AUTO_DELETE_WINDOW_DAYS = 30L
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /** Whole days remaining before [entry] auto-deletes, clamped to 0 (never negative). */
    fun daysLeft(
        entry: RecycleBinEntry,
        now: Long,
        windowDays: Long = AUTO_DELETE_WINDOW_DAYS,
    ): Long {
        val elapsedDays = (now - entry.deletedAt) / MILLIS_PER_DAY
        return (windowDays - elapsedDays).coerceAtLeast(0L)
    }

    /** True once [entry] has sat in the bin past the auto-delete window. */
    fun isExpired(
        entry: RecycleBinEntry,
        now: Long,
        windowDays: Long = AUTO_DELETE_WINDOW_DAYS,
    ): Boolean = now - entry.deletedAt >= windowDays * MILLIS_PER_DAY

    /** Partition [entries] into (expired → purge, surviving → keep) at [now]. */
    fun partitionExpired(
        entries: List<RecycleBinEntry>,
        now: Long,
        windowDays: Long = AUTO_DELETE_WINDOW_DAYS,
    ): Pair<List<RecycleBinEntry>, List<RecycleBinEntry>> = entries.partition { isExpired(it, now, windowDays) }
}
