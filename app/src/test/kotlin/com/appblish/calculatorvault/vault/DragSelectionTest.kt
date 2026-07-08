package com.appblish.calculatorvault.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks in the long-press-drag range semantics (W1-E3, S17): the live selection is always
 * the drag-start base plus the anchor-to-target range in display order, in either drag
 * direction, and dragging back releases only what the gesture itself swept up — never the
 * pre-existing selection.
 */
class DragSelectionTest {
    private val order = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun `dragging forward selects the contiguous anchor-to-target range`() {
        val selected = DragSelection.rangeSelect(order, base = setOf("b"), anchor = "b", target = "e")
        assertThat(selected).containsExactly("b", "c", "d", "e")
    }

    @Test
    fun `dragging backwards from the anchor selects the reversed range`() {
        val selected = DragSelection.rangeSelect(order, base = setOf("d"), anchor = "d", target = "a")
        assertThat(selected).containsExactly("a", "b", "c", "d")
    }

    @Test
    fun `retreating the drag releases swept items but never the pre-drag selection`() {
        // "f" was selected before the drag began; the drag sweeps b..e then retreats to c.
        val base = setOf("b", "f")
        val atFurthest = DragSelection.rangeSelect(order, base, anchor = "b", target = "e")
        assertThat(atFurthest).containsExactly("b", "c", "d", "e", "f")

        val retreated = DragSelection.rangeSelect(order, base, anchor = "b", target = "c")
        assertThat(retreated).containsExactly("b", "c", "f")
    }

    @Test
    fun `target equal to anchor keeps just the base`() {
        val selected = DragSelection.rangeSelect(order, base = setOf("c"), anchor = "c", target = "c")
        assertThat(selected).containsExactly("c")
    }

    @Test
    fun `an id missing from the display order keeps the base selection intact`() {
        val base = setOf("a", "b")
        assertThat(DragSelection.rangeSelect(order, base, anchor = "zz", target = "d")).isEqualTo(base)
        assertThat(DragSelection.rangeSelect(order, base, anchor = "a", target = "zz")).isEqualTo(base)
    }
}
