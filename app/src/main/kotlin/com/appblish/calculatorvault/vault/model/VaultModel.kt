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
)

/**
 * Where an un-hide writes the decrypted bytes. [Original] targets the album the file was
 * hidden from (from the encrypted index); [Chosen] targets a caller-picked public
 * RELATIVE_PATH. Either way, if the primary destination is missing/unwritable the
 * repository falls back to Downloads and reports it — spec §1.4: never fail silently.
 */
sealed interface UnhideDestination {
    data object Original : UnhideDestination

    data class Chosen(
        val relativePath: String,
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

/** A user-created folder within a category (Create Folder from the FAB menu). */
data class VaultFolder(
    val id: String,
    val category: VaultCategory,
    val name: String,
    val itemCount: Int = 0,
)

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
