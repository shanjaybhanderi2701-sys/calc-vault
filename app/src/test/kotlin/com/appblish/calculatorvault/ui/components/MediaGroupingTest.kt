package com.appblish.calculatorvault.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Covers the pure date-grouping used by [DateGroupedMediaGrid]. */
class MediaGroupingTest {
    @Test
    fun `groups items by date label`() {
        val items =
            listOf(
                MediaItem("a", "Today", 30),
                MediaItem("b", "Yesterday", 20),
                MediaItem("c", "Today", 29),
            )

        val groups = groupMediaByDate(items)

        assertThat(groups.map { it.dateLabel }).containsExactly("Today", "Yesterday").inOrder()
        assertThat(groups.first().items.map { it.id }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `orders sections newest first by max sort key`() {
        val items =
            listOf(
                MediaItem("old", "12 Jun", 100),
                MediaItem("new", "Today", 900),
                MediaItem("mid", "Yesterday", 500),
            )

        val labels = groupMediaByDate(items).map { it.dateLabel }

        assertThat(labels).containsExactly("Today", "Yesterday", "12 Jun").inOrder()
    }

    @Test
    fun `orders items within a section newest first`() {
        val items =
            listOf(
                MediaItem("first", "Today", 10),
                MediaItem("second", "Today", 50),
                MediaItem("third", "Today", 30),
            )

        val ids = groupMediaByDate(items).single().items.map { it.id }

        assertThat(ids).containsExactly("second", "third", "first").inOrder()
    }

    @Test
    fun `empty input yields no groups`() {
        assertThat(groupMediaByDate(emptyList())).isEmpty()
    }
}
