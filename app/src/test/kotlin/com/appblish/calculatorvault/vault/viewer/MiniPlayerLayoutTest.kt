package com.appblish.calculatorvault.vault.viewer

import com.appblish.calculatorvault.vault.viewer.MiniPlayerLayout.Mode
import com.appblish.calculatorvault.vault.viewer.MiniPlayerLayout.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-351 (Wave 4) — pure Mini Player layout / mode core (spec §5c), pinned on the JVM so the
 * "Mini Player floats IN-APP ONLY; drag, expand, close all work" DoD item is proven by the
 * rules that keep the floating window inside the app's content bounds (the privacy invariant)
 * and drive the Full ⇄ Mini ⇄ Closed machine.
 */
class MiniPlayerLayoutTest {
    // 1080 x 1920 content area, a 480 x 270 mini window (16:9) — a typical phone surface.
    private val cw = 1080f
    private val ch = 1920f
    private val pw = 480f
    private val ph = 270f

    // ---- clampOffset: the privacy invariant — window can never leave the content area ----

    @Test
    fun `clamp keeps an in-bounds position unchanged`() {
        val o = MiniPlayerLayout.clampOffset(cw, ch, pw, ph, x = 100f, y = 200f)
        assertThat(o).isEqualTo(Offset(100f, 200f))
    }

    @Test
    fun `clamp pins a position dragged past the right and bottom edges back inside`() {
        val o = MiniPlayerLayout.clampOffset(cw, ch, pw, ph, x = 5000f, y = 5000f)
        assertThat(o).isEqualTo(Offset(cw - pw, ch - ph))
    }

    @Test
    fun `clamp pins a negative position back to the top-left origin`() {
        val o = MiniPlayerLayout.clampOffset(cw, ch, pw, ph, x = -300f, y = -80f)
        assertThat(o).isEqualTo(Offset(0f, 0f))
    }

    @Test
    fun `a window larger than the container pins to origin on that axis`() {
        val o = MiniPlayerLayout.clampOffset(cw, ch, playerW = 2000f, playerH = 3000f, x = 400f, y = 400f)
        assertThat(o).isEqualTo(Offset(0f, 0f))
    }

    // ---- drag: delta + re-clamp on every gesture step ----

    @Test
    fun `drag moves by the delta while inside bounds`() {
        val start = Offset(100f, 100f)
        val moved = MiniPlayerLayout.drag(cw, ch, pw, ph, start, dx = 50f, dy = -30f)
        assertThat(moved).isEqualTo(Offset(150f, 70f))
    }

    @Test
    fun `drag past the edge is clamped, not lost — window stays fully on-screen`() {
        val start = Offset(cw - pw, ch - ph) // already at bottom-right
        val moved = MiniPlayerLayout.drag(cw, ch, pw, ph, start, dx = 200f, dy = 200f)
        assertThat(moved).isEqualTo(Offset(cw - pw, ch - ph))
    }

    // ---- initialOffset: rest in the bottom-end corner, inset by the margin ----

    @Test
    fun `initial position is the bottom-end corner inset by the margin`() {
        val o = MiniPlayerLayout.initialOffset(cw, ch, pw, ph)
        val m = MiniPlayerLayout.DEFAULT_MARGIN
        assertThat(o).isEqualTo(Offset(cw - pw - m, ch - ph - m))
    }

    // ---- nearestCorner: a dropped window snaps to the closest tidy corner ----

    @Test
    fun `nearest corner snaps a top-left-ish drop to the top-left slot`() {
        val o = MiniPlayerLayout.nearestCorner(cw, ch, pw, ph, current = Offset(60f, 90f))
        val m = MiniPlayerLayout.DEFAULT_MARGIN
        assertThat(o).isEqualTo(Offset(m, m))
    }

    @Test
    fun `nearest corner snaps a bottom-right-ish drop to the bottom-right slot`() {
        val o = MiniPlayerLayout.nearestCorner(cw, ch, pw, ph, current = Offset(cw - pw - 40f, ch - ph - 40f))
        val m = MiniPlayerLayout.DEFAULT_MARGIN
        assertThat(o).isEqualTo(Offset(cw - pw - m, ch - ph - m))
    }

    // ---- mode machine: Full -> Mini -> Full, Close from anywhere ----

    @Test
    fun `minimize goes Full to Mini and is a no-op from Mini or Closed`() {
        assertThat(MiniPlayerLayout.minimize(Mode.FULL)).isEqualTo(Mode.MINI)
        assertThat(MiniPlayerLayout.minimize(Mode.MINI)).isEqualTo(Mode.MINI)
        assertThat(MiniPlayerLayout.minimize(Mode.CLOSED)).isEqualTo(Mode.CLOSED)
    }

    @Test
    fun `expand goes Mini to Full and is a no-op otherwise`() {
        assertThat(MiniPlayerLayout.expand(Mode.MINI)).isEqualTo(Mode.FULL)
        assertThat(MiniPlayerLayout.expand(Mode.FULL)).isEqualTo(Mode.FULL)
        assertThat(MiniPlayerLayout.expand(Mode.CLOSED)).isEqualTo(Mode.CLOSED)
    }

    @Test
    fun `close always stops and dismisses`() {
        assertThat(MiniPlayerLayout.close()).isEqualTo(Mode.CLOSED)
    }

    @Test
    fun `isActive is true only while a player surface holds the ExoPlayer`() {
        assertThat(MiniPlayerLayout.isActive(Mode.FULL)).isTrue()
        assertThat(MiniPlayerLayout.isActive(Mode.MINI)).isTrue()
        assertThat(MiniPlayerLayout.isActive(Mode.CLOSED)).isFalse()
    }
}
