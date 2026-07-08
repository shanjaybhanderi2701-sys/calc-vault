package com.appblish.calculatorvault.vault.model

/**
 * Grid sort model for the Wave-3 organization polish (W3-E, spec §4.1 / design W3-D §7).
 * A sort is a key × direction pair; the *photo* grid offers all four keys, the *album*
 * grid only the three that exist for albums — Date taken is a photo-capture property and
 * is never faked for albums (design decision G-7).
 *
 * Everything here is pure Kotlin (no Android/Compose deps) so the exact orderings the CI
 * DoD asserts — every key × direction, the missing-Date-taken fallback, the deterministic
 * Name-ascending tiebreak — are unit-testable.
 */
enum class SortKey(
    val label: String,
) {
    NAME("Name"),
    SIZE("Size"),
    LAST_MODIFIED("Last modified"),
    DATE_TAKEN("Date taken"),
    ;

    companion object {
        /** The photo grid's key set — exactly spec §4.1's four, in sheet order. */
        val PHOTO_KEYS: List<SortKey> = listOf(NAME, SIZE, LAST_MODIFIED, DATE_TAKEN)

        /** The album grid's key set — Date taken omitted (design G-7). */
        val ALBUM_KEYS: List<SortKey> = listOf(NAME, SIZE, LAST_MODIFIED)
    }
}

enum class SortDirection(
    val label: String,
) {
    ASCENDING("Ascending"),
    DESCENDING("Descending"),
}

/**
 * One grid's active sort. Persisted in the encrypted index as `"KEY:DIRECTION"` — the
 * encoding is part of the index format, so [decode] must keep accepting every string
 * [encode] has ever produced (unknown/corrupt values fall back to the caller's default,
 * never throw: an old index must always open).
 */
data class GridSort(
    val key: SortKey,
    val direction: SortDirection,
) {
    fun encode(): String = "${key.name}:${direction.name}"

    companion object {
        /** Photo-grid first-run default: newest first, gallery muscle memory (W3-D §7). */
        val PHOTO_DEFAULT = GridSort(SortKey.DATE_TAKEN, SortDirection.DESCENDING)

        /** Album-grid first-run default (W3-D §7). */
        val ALBUM_DEFAULT = GridSort(SortKey.NAME, SortDirection.ASCENDING)

        fun decode(
            encoded: String?,
            default: GridSort,
        ): GridSort {
            val parts = encoded?.split(':') ?: return default
            if (parts.size != 2) return default
            val key = SortKey.entries.firstOrNull { it.name == parts[0] } ?: return default
            val direction = SortDirection.entries.firstOrNull { it.name == parts[1] } ?: return default
            return GridSort(key, direction)
        }
    }
}

/**
 * The vault-wide persisted sort choices, one per grid type (spec §4.1 "persisted choice",
 * surviving process death and lock/unlock). The per-album photo override lives on the
 * album label itself ([VaultFolder.photoSortOverride]), not here.
 */
data class SortPrefs(
    val photoSort: GridSort = GridSort.PHOTO_DEFAULT,
    val albumSort: GridSort = GridSort.ALBUM_DEFAULT,
)

/**
 * Order [items] for the photo grid — reads **only** index metadata (spec §4.1: zero
 * decryption to sort). Key values:
 *
 *  - **Name** — original file name, case-insensitive.
 *  - **Size** — stored blob size.
 *  - **Last modified** — the original's modified time captured at hide time
 *    ([VaultItem.dateModifiedMs]); 0 (unknown) falls back to the added-to-vault time so a
 *    legacy item still sorts somewhere sensible instead of clumping at the epoch.
 *  - **Date taken** — capture time ([VaultItem.dateTakenMs]); an item with no capture
 *    time falls back to its Last-modified value *for that item* — no "Unknown" bucket
 *    (design G-decision in W3-D §7).
 *
 * Tiebreak everywhere: Name ascending, then id — stable and deterministic, so CI can
 * assert exact orders.
 */
fun sortItems(
    items: List<VaultItem>,
    sort: GridSort,
): List<VaultItem> {
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { item: VaultItem -> item.originalName }.thenBy { it.id }
    val comparator =
        when (sort.key) {
            SortKey.NAME -> byName
            SortKey.SIZE -> directional(sort.direction, byName) { it.sizeBytes }
            SortKey.LAST_MODIFIED -> directional(sort.direction, byName) { it.lastModifiedValue() }
            SortKey.DATE_TAKEN -> directional(sort.direction, byName) { it.dateTakenValue() }
        }
    return when {
        sort.key == SortKey.NAME && sort.direction == SortDirection.DESCENDING ->
            items.sortedWith(byName.reversed())
        sort.key == SortKey.NAME -> items.sortedWith(byName)
        else -> items.sortedWith(comparator)
    }
}

/** Last-modified sort value: hide-time captured modified date, else added-to-vault time. */
private fun VaultItem.lastModifiedValue(): Long = if (dateModifiedMs > 0) dateModifiedMs else sortKey

/** Date-taken sort value: capture time, else the item's Last-modified value (W3-D §7). */
private fun VaultItem.dateTakenValue(): Long = if (dateTakenMs > 0) dateTakenMs else lastModifiedValue()

/** [selector] in [direction], with the Name-ascending [tiebreak] appended un-reversed. */
private fun directional(
    direction: SortDirection,
    tiebreak: Comparator<VaultItem>,
    selector: (VaultItem) -> Long,
): Comparator<VaultItem> {
    val primary = compareBy(selector)
    return (if (direction == SortDirection.DESCENDING) primary.reversed() else primary).then(tiebreak)
}
