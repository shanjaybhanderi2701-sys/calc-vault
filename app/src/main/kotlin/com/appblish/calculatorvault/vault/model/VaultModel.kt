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
    // gallery album. Persisted. Null when unknown → un-hide falls back to a per-category
    // default public folder (see MediaSink.defaultRelativePath).
    val relativePath: String? = null,
)

/** A user-created folder within a category (Create Folder from the FAB menu). */
data class VaultFolder(
    val id: String,
    val category: VaultCategory,
    val name: String,
    val itemCount: Int = 0,
)

/**
 * The predefined folders seeded into every fresh vault the first time it is initialized.
 * Build spec §4 (APP-225, board-ruled on APP-220): each of the three Phase-1 categories —
 * Photos, Videos, Audio — is created on first use, even when empty, containing exactly one
 * default empty **"Download"** folder. Seeding happens once per vault *namespace*: the real
 * vault and each decoy slot (`decoy_<slot>/`) seed into their own encrypted index, so a
 * decoy's folders can never leak into the real vault.
 *
 * Each default folder carries a **stable, derived id** (`seed_<category>_<slug>`) so seeding
 * is idempotent and a folder the user later renames or deletes is never resurrected.
 */
object DefaultVaultFolders {
    /** (category, display name) pairs seeded into a fresh vault, in display order. */
    private val CATALOG: List<Pair<VaultCategory, String>> =
        VaultCategory.PHASE1.map { it to "Download" }

    /** The default folders for a brand-new vault namespace, with stable ids. */
    fun forFreshVault(): List<VaultFolder> =
        CATALOG.map { (category, name) ->
            VaultFolder(id = seedId(category, name), category = category, name = name)
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
