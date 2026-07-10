package com.appblish.calculatorvault.vault.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-350 (Wave 3) — order-mode transitions for the playlist (spec §5d), pinned so the DoD
 * "Order modes (Order/Shuffle/Repeat Current/Loop All/No Loop) all behave correctly" is proven
 * by the rules. Auto-advance ([PlaylistEngine.onCompletion]) honours loop mode; manual Next/Prev
 * always wrap.
 */
class PlaylistEngineTest {
    // ---- auto-advance on completion, per mode ----

    @Test
    fun `repeat current replays the same index`() {
        assertThat(PlaylistEngine.onCompletion(5, 2, OrderMode.REPEAT_CURRENT)).isEqualTo(2)
    }

    @Test
    fun `loop all wraps past the end back to the start`() {
        assertThat(PlaylistEngine.onCompletion(5, 3, OrderMode.LOOP_ALL)).isEqualTo(4)
        assertThat(PlaylistEngine.onCompletion(5, 4, OrderMode.LOOP_ALL)).isEqualTo(0)
    }

    @Test
    fun `order and no-loop advance then stop at the end`() {
        assertThat(PlaylistEngine.onCompletion(5, 3, OrderMode.ORDER)).isEqualTo(4)
        assertThat(PlaylistEngine.onCompletion(5, 4, OrderMode.ORDER)).isNull()
        assertThat(PlaylistEngine.onCompletion(5, 4, OrderMode.NO_LOOP)).isNull()
    }

    @Test
    fun `shuffle advances within its play order then stops at the end`() {
        assertThat(PlaylistEngine.onCompletion(5, 1, OrderMode.SHUFFLE)).isEqualTo(2)
        assertThat(PlaylistEngine.onCompletion(5, 4, OrderMode.SHUFFLE)).isNull()
    }

    @Test
    fun `empty playlist never advances`() {
        assertThat(PlaylistEngine.onCompletion(0, 0, OrderMode.LOOP_ALL)).isNull()
        assertThat(PlaylistEngine.manualNext(0, 0)).isNull()
        assertThat(PlaylistEngine.manualPrev(0, 0)).isNull()
    }

    // ---- manual Next / Prev always wrap regardless of mode ----

    @Test
    fun `manual next wraps at the end`() {
        assertThat(PlaylistEngine.manualNext(5, 2)).isEqualTo(3)
        assertThat(PlaylistEngine.manualNext(5, 4)).isEqualTo(0)
    }

    @Test
    fun `manual prev wraps at the start`() {
        assertThat(PlaylistEngine.manualPrev(5, 2)).isEqualTo(1)
        assertThat(PlaylistEngine.manualPrev(5, 0)).isEqualTo(4)
    }

    @Test
    fun `out of range current index is coerced before advancing`() {
        assertThat(PlaylistEngine.onCompletion(3, 99, OrderMode.LOOP_ALL)).isEqualTo(0)
        assertThat(PlaylistEngine.manualNext(3, -5)).isEqualTo(1)
    }

    // ---- deterministic shuffle ----

    @Test
    fun `shuffled order is a permutation of all indices`() {
        val order = PlaylistEngine.shuffledOrder(6, seed = 42L)
        assertThat(order).containsExactly(0, 1, 2, 3, 4, 5)
    }

    @Test
    fun `shuffle is deterministic for a given seed and varies across seeds`() {
        val a = PlaylistEngine.shuffledOrder(8, seed = 7L)
        val b = PlaylistEngine.shuffledOrder(8, seed = 7L)
        val c = PlaylistEngine.shuffledOrder(8, seed = 99L)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun `shuffle handles empty and singleton lists`() {
        assertThat(PlaylistEngine.shuffledOrder(0, 1L)).isEmpty()
        assertThat(PlaylistEngine.shuffledOrder(1, 1L)).containsExactly(0)
    }
}
