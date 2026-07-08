package com.appblish.calculatorvault.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the in-vault picker's sort menu ([PickerSort.grid]). The grid always
 * renders by descending sortKey within each date section, so these assertions verify the
 * synthetic keys/labels encode the intended order for each xlock sort option.
 */
class PickerSortTest {
    private fun src(
        id: String,
        name: String,
        added: Long,
        modified: Long,
        size: Long,
        dateLabel: String = "Today",
    ) = SourceItem(
        id = id,
        name = name,
        dateLabel = dateLabel,
        sortKey = added,
        albumId = "a",
        albumName = "A",
        sizeBytes = size,
        dateModified = modified,
    )

    private val items =
        listOf(
            src("1", "banana.jpg", added = 300, modified = 100, size = 50, dateLabel = "Today"),
            src("2", "apple.jpg", added = 100, modified = 300, size = 90, dateLabel = "Yesterday"),
            src("3", "cherry.jpg", added = 200, modified = 200, size = 10, dateLabel = "Today"),
        )

    /** ADDED_TIME preserves each item's real date section and its recency key. */
    @Test
    fun addedTime_keepsRealDateLabelsAndKeys() {
        val grid = PickerSort.grid(items, PickerSort.ADDED_TIME)
        assertEquals(listOf("1", "2", "3"), grid.map { it.first })
        assertEquals(listOf("Today", "Yesterday", "Today"), grid.map { it.second })
        assertEquals(listOf(300L, 100L, 200L), grid.map { it.third })
    }

    /** NAME collapses to one A→Z section; descending keys put the first name highest. */
    @Test
    fun name_ordersAlphabeticallyViaDescendingKeys() {
        val grid = PickerSort.grid(items, PickerSort.NAME)
        assertEquals(listOf("2", "1", "3"), grid.map { it.first }) // apple, banana, cherry
        grid.forEach { assertEquals("By name (A–Z)", it.second) }
        // Keys strictly descending in alphabetical order.
        assertEquals(listOf(3L, 2L, 1L), grid.map { it.third })
    }

    /** SIZE uses raw byte size as the key so the grid shows largest first. */
    @Test
    fun size_usesByteSizeAsKey() {
        val grid = PickerSort.grid(items, PickerSort.SIZE)
        assertEquals(setOf("1", "2", "3"), grid.map { it.first }.toSet())
        assertEquals(mapOf("1" to 50L, "2" to 90L, "3" to 10L), grid.associate { it.first to it.third })
        grid.forEach { assertEquals("Largest first", it.second) }
    }

    /** LAST_MODIFIED uses DATE_MODIFIED as the key so the grid shows most-recent first. */
    @Test
    fun lastModified_usesModifiedTimeAsKey() {
        val grid = PickerSort.grid(items, PickerSort.LAST_MODIFIED)
        assertEquals(mapOf("1" to 100L, "2" to 300L, "3" to 200L), grid.associate { it.first to it.third })
        grid.forEach { assertEquals("Last modified", it.second) }
    }
}
