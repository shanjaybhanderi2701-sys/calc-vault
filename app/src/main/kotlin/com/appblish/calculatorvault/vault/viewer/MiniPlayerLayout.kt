package com.appblish.calculatorvault.vault.viewer

/**
 * CalcVault Phase B · Wave 4 · APP-351 — pure layout / state core for the **in-app Mini
 * Player** (spec §5c). Like the Wave-3 cores ([VideoScaleMath], [VideoZoomMath]) every rule
 * that decides *where the floating window sits* and *which mode the player is in* lives here
 * as a side-effect-free function so it is unit-testable on the JVM; the Compose layer only
 * feeds it drag deltas and container/window sizes and applies the returned offset.
 *
 * **Privacy is a layout invariant (spec §5c).** The Mini Player is an ordinary Compose
 * overlay drawn *inside the app's own window* over the Video Vault surface — it is NOT a
 * system overlay (the app declares no `SYSTEM_ALERT_WINDOW`; see AndroidManifest scope
 * guard). Because it can never leave the app's content bounds, [clampOffset] additionally
 * guarantees the window is always fully on-screen: hidden vault content can never be dragged
 * partially off the app surface into a place the lock does not cover. Drag, therefore, is
 * bound math, not a free translation.
 *
 * Playlist Next/Prev reuse the already-tested [PlaylistEngine] (manualNext / manualPrev) — the
 * Mini Player is the same playlist, just a smaller chrome — so this core owns only the window
 * geometry and the mode machine.
 */
object MiniPlayerLayout {
    /** The three states the video player moves between (spec §5c Mini Player + Expand/Close). */
    enum class Mode {
        /** The full-screen in-viewer player (default). */
        FULL,

        /** The floating, draggable mini window over the vault surface. */
        MINI,

        /** Stopped + dismissed — no player, no playback. */
        CLOSED,
    }

    /** A window top-left position, in pixels within the app content area. Pure data — no Android. */
    data class Offset(
        val x: Float,
        val y: Float,
    )

    /** Default inset (px) the mini window keeps from every content edge when snapped/placed. */
    const val DEFAULT_MARGIN: Float = 24f

    /**
     * Clamp a candidate top-left [x]/[y] so a [playerW]×[playerH] mini window stays **fully
     * inside** the [containerW]×[containerH] app content area — the privacy invariant: the
     * window (and the vault frame it shows) can never be dragged past the app's own edge.
     *
     * A window larger than the container on an axis pins to 0 on that axis (defensive; the
     * mini window is always far smaller than the surface in practice).
     */
    fun clampOffset(
        containerW: Float,
        containerH: Float,
        playerW: Float,
        playerH: Float,
        x: Float,
        y: Float,
    ): Offset {
        val maxX = (containerW - playerW).coerceAtLeast(0f)
        val maxY = (containerH - playerH).coerceAtLeast(0f)
        return Offset(
            x = x.coerceIn(0f, maxX),
            y = y.coerceIn(0f, maxY),
        )
    }

    /**
     * Apply a drag delta ([dx], [dy]) to the current [current] position and re-clamp. The
     * Compose layer calls this on every `detectDragGestures` step so the window follows the
     * finger but is physically incapable of leaving the content bounds.
     */
    fun drag(
        containerW: Float,
        containerH: Float,
        playerW: Float,
        playerH: Float,
        current: Offset,
        dx: Float,
        dy: Float,
    ): Offset = clampOffset(containerW, containerH, playerW, playerH, current.x + dx, current.y + dy)

    /**
     * The window's resting spot when the player first minimizes: the **bottom-end corner**,
     * inset by [margin] — matching the reference player and keeping it clear of the vault's
     * top bar. Falls out to a clamped position on tiny surfaces.
     */
    fun initialOffset(
        containerW: Float,
        containerH: Float,
        playerW: Float,
        playerH: Float,
        margin: Float = DEFAULT_MARGIN,
    ): Offset =
        clampOffset(
            containerW,
            containerH,
            playerW,
            playerH,
            x = containerW - playerW - margin,
            y = containerH - playerH - margin,
        )

    /**
     * Snap a dropped window to the **nearest of the four corners** (each inset by [margin]),
     * so a fling always lands somewhere tidy rather than mid-edge. Nearest is measured from the
     * window's own centre to each corner slot's centre.
     */
    fun nearestCorner(
        containerW: Float,
        containerH: Float,
        playerW: Float,
        playerH: Float,
        current: Offset,
        margin: Float = DEFAULT_MARGIN,
    ): Offset {
        val left = margin
        val top = margin
        val right = (containerW - playerW - margin).coerceAtLeast(0f)
        val bottom = (containerH - playerH - margin).coerceAtLeast(0f)
        val corners =
            listOf(
                Offset(left, top),
                Offset(right, top),
                Offset(left, bottom),
                Offset(right, bottom),
            )
        val cx = current.x + playerW / 2f
        val cy = current.y + playerH / 2f
        val nearest =
            corners.minByOrNull { c ->
                val dx = (c.x + playerW / 2f) - cx
                val dy = (c.y + playerH / 2f) - cy
                dx * dx + dy * dy
            } ?: corners.first()
        return clampOffset(containerW, containerH, playerW, playerH, nearest.x, nearest.y)
    }

    /**
     * The mode after tapping the **Mini Player** control from the full player: FULL → MINI.
     * From any other state it is a no-op (already minimized, or closed → nothing to minimize).
     */
    fun minimize(current: Mode): Mode = if (current == Mode.FULL) Mode.MINI else current

    /** Tapping **Expand** on the mini window: MINI → FULL. No-op otherwise. */
    fun expand(current: Mode): Mode = if (current == Mode.MINI) Mode.FULL else current

    /**
     * Tapping **Close** on the mini window: stop + dismiss from either visible state. Idempotent
     * once already CLOSED. The Compose/ViewModel layer releases the ExoPlayer on this transition.
     */
    fun close(): Mode = Mode.CLOSED

    /** True while a player surface (full or mini) should be composed and holding the ExoPlayer. */
    fun isActive(mode: Mode): Boolean = mode == Mode.FULL || mode == Mode.MINI
}
