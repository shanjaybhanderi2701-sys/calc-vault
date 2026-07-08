package com.appblish.calculatorvault.vault

import com.appblish.calculatorvault.vault.model.GridSort
import com.appblish.calculatorvault.vault.model.SortDirection
import com.appblish.calculatorvault.vault.model.SortKey
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.model.sortItems
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * W3-E §7 DoD (spec §4.1): the photo-grid orderings are exact and deterministic — all
 * four keys × both directions, the missing-Date-taken per-item fallback (no "Unknown"
 * bucket), the Name-ascending tiebreak, and the index-string round-trip of the persisted
 * choice (an old index must always decode, unknown values falling back to the default).
 */
class VaultSortTest {
    private fun item(
        id: String,
        name: String,
        size: Long = 0,
        modified: Long = 0,
        taken: Long = 0,
        added: Long = 0,
    ) = VaultItem(
        id = id,
        category = VaultCategory.PHOTOS,
        originalName = name,
        dateLabel = "Today",
        sortKey = added,
        sizeBytes = size,
        dateModifiedMs = modified,
        dateTakenMs = taken,
    )

    private fun ids(
        items: List<VaultItem>,
        key: SortKey,
        direction: SortDirection,
    ) = sortItems(items, GridSort(key, direction)).map { it.id }

    @Test
    fun `all four keys sort both directions deterministically`() {
        val items =
            listOf(
                item("a", "banana.jpg", size = 30, modified = 10, taken = 300),
                item("b", "Apple.jpg", size = 10, modified = 30, taken = 100),
                item("c", "cherry.jpg", size = 20, modified = 20, taken = 200),
            )
        assertThat(ids(items, SortKey.NAME, SortDirection.ASCENDING)).containsExactly("b", "a", "c").inOrder()
        assertThat(ids(items, SortKey.NAME, SortDirection.DESCENDING)).containsExactly("c", "a", "b").inOrder()
        assertThat(ids(items, SortKey.SIZE, SortDirection.ASCENDING)).containsExactly("b", "c", "a").inOrder()
        assertThat(ids(items, SortKey.SIZE, SortDirection.DESCENDING)).containsExactly("a", "c", "b").inOrder()
        assertThat(ids(items, SortKey.LAST_MODIFIED, SortDirection.ASCENDING))
            .containsExactly("a", "c", "b")
            .inOrder()
        assertThat(ids(items, SortKey.LAST_MODIFIED, SortDirection.DESCENDING))
            .containsExactly("b", "c", "a")
            .inOrder()
        assertThat(ids(items, SortKey.DATE_TAKEN, SortDirection.ASCENDING))
            .containsExactly("b", "c", "a")
            .inOrder()
        assertThat(ids(items, SortKey.DATE_TAKEN, SortDirection.DESCENDING))
            .containsExactly("a", "c", "b")
            .inOrder()
    }

    @Test
    fun `missing date taken falls back to that item's last modified value`() {
        // "b" has no capture time → its Date-taken value is its modified time (150),
        // slotting it BETWEEN the two captured items — never an "Unknown" bucket.
        val items =
            listOf(
                item("a", "a.jpg", taken = 100, modified = 999),
                item("b", "b.jpg", taken = 0, modified = 150),
                item("c", "c.jpg", taken = 200, modified = 1),
            )
        assertThat(ids(items, SortKey.DATE_TAKEN, SortDirection.ASCENDING))
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    @Test
    fun `key ties break by name ascending in either direction`() {
        val items =
            listOf(
                item("x", "zebra.jpg", size = 10),
                item("y", "alpha.jpg", size = 10),
                item("z", "Mango.jpg", size = 10),
            )
        // All sizes equal → pure Name-ascending tiebreak, same for both directions.
        assertThat(ids(items, SortKey.SIZE, SortDirection.ASCENDING)).containsExactly("y", "z", "x").inOrder()
        assertThat(ids(items, SortKey.SIZE, SortDirection.DESCENDING)).containsExactly("y", "z", "x").inOrder()
    }

    @Test
    fun `grid sort encodes and decodes with safe fallbacks`() {
        val sort = GridSort(SortKey.LAST_MODIFIED, SortDirection.DESCENDING)
        assertThat(GridSort.decode(sort.encode(), GridSort.PHOTO_DEFAULT)).isEqualTo(sort)
        assertThat(GridSort.decode(null, GridSort.PHOTO_DEFAULT)).isEqualTo(GridSort.PHOTO_DEFAULT)
        assertThat(GridSort.decode("garbage", GridSort.ALBUM_DEFAULT)).isEqualTo(GridSort.ALBUM_DEFAULT)
        assertThat(GridSort.decode("NOPE:DESCENDING", GridSort.ALBUM_DEFAULT)).isEqualTo(GridSort.ALBUM_DEFAULT)
    }
}
