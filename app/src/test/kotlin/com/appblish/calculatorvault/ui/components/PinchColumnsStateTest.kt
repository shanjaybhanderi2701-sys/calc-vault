package com.appblish.calculatorvault.ui.components

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.math.sqrt

/**
 * APP-293 item 10 — the pinch-to-change-columns math. The smoothness contract lives in
 * the flip rule: the column count changes at the geometric midpoint between two layouts,
 * and the visual scale is compensated by the exact layout ratio so the on-screen tile
 * size is continuous through the reflow (no snap).
 */
class PinchColumnsStateTest {
    private fun state(
        initial: Int = 3,
        min: Int = 2,
        max: Int = 5,
    ) = PinchColumnsState(initial, min, max, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `small pinch scales visually without flipping columns`() {
        val s = state()
        s.onPinch(1.1f)
        assertThat(s.columns).isEqualTo(3)
        assertThat(s.visualScale).isWithin(1e-4f).of(1.1f)
    }

    @Test
    fun `pinching out past the midpoint drops a column and compensates the scale`() {
        val s = state()
        // Midpoint 3→2 is √(3/2) ≈ 1.2247; crossing it flips and divides by 3/2.
        s.onPinch(1.25f)
        assertThat(s.columns).isEqualTo(2)
        // Displayed tile size stays continuous: 1.25 * (2/3) ≈ 0.8333.
        assertThat(s.visualScale).isWithin(1e-4f).of(1.25f * 2f / 3f)
    }

    @Test
    fun `pinching in past the midpoint adds a column and compensates the scale`() {
        val s = state()
        // Midpoint 3→4 is 1/√(4/3) = √(3/4) ≈ 0.866; crossing flips and multiplies by 4/3.
        s.onPinch(0.85f)
        assertThat(s.columns).isEqualTo(4)
        assertThat(s.visualScale).isWithin(1e-4f).of(0.85f * 4f / 3f)
    }

    @Test
    fun `columns clamp at the bounds no matter how far the pinch goes`() {
        val s = state()
        s.onPinch(10f)
        assertThat(s.columns).isEqualTo(2)
        s.onPinch(0.01f)
        assertThat(s.columns).isEqualTo(5)
    }

    @Test
    fun `the grid range reaches a dense 6 columns (APP-314 item 3)`() {
        // The vault grids now pinch all the way to 6 (was capped at 5); a hard pinch-in from
        // the default 3 must walk up to the new max and clamp there.
        val s = state(initial = 3, min = 2, max = 6)
        s.onPinch(0.01f)
        assertThat(s.columns).isEqualTo(6)
    }

    @Test
    fun `a big continuous pinch crosses multiple column counts`() {
        val s = state(initial = 5, min = 2, max = 5)
        // 5→2 needs a total ratio of 5/2 = 2.5; one large pinch should walk all the way.
        s.onPinch(2.6f)
        assertThat(s.columns).isEqualTo(2)
    }

    @Test
    fun `displayed tile size is continuous across a flip`() {
        val s = state()
        // Just below the 3→2 midpoint: no flip yet.
        val justBelow = sqrt(3f / 2f) - 0.01f
        s.onPinch(justBelow)
        val displayedBefore = s.visualScale / s.columns
        // Nudge across the midpoint.
        s.onPinch((sqrt(3f / 2f) + 0.01f) / justBelow)
        val displayedAfter = s.visualScale / s.columns
        assertThat(displayedAfter).isWithin(0.02f).of(displayedBefore)
    }
}
