package com.appblish.calculatorvault.vault.viewer

import java.util.Random

/**
 * CalcVault Phase B · Wave 3 · APP-350 — pure playlist / order-mode core for the video player
 * (spec §5d). The playlist is "all videos in the current folder/album"; this object decides
 * where playback goes on **auto-advance** (a track finished) and on **manual Next / Prev**,
 * under each of the five order modes. Kept side-effect-free so every transition is
 * unit-testable on the JVM; the ViewModel owns the item list and current index and just calls
 * these to pick the next index.
 *
 * **Indices are positions in the current play order.** For [OrderMode.SHUFFLE] the ViewModel
 * holds a shuffled permutation (built once via [shuffledOrder]) and advances *within* it, so
 * the same index arithmetic works for every mode.
 *
 * **Manual vs auto-advance differ by design.** Loop mode governs what happens when a video
 * *ends on its own*; manual Next/Prev always wrap so the user is never stuck at a boundary
 * (matches the reference player). Only [onCompletion] honours "stop at end".
 */
enum class OrderMode(val label: String) {
    /** Play in the folder's original sequence; stop after the last video. */
    ORDER("Order"),

    /** Play the folder in a random permutation; stop after the last shuffled video. */
    SHUFFLE("Shuffle"),

    /** Repeat the current video indefinitely. */
    REPEAT_CURRENT("Repeat Current"),

    /** Play through and restart from the first video after the last (loops forever). */
    LOOP_ALL("Loop All"),

    /** Play the playlist once and stop after the final video. */
    NO_LOOP("No Loop"),
}

object PlaylistEngine {
    /**
     * Where playback goes when the current video **finishes on its own**, or `null` to stop:
     *  - [OrderMode.REPEAT_CURRENT] → the same index (replays).
     *  - [OrderMode.LOOP_ALL] → next, wrapping past the end back to 0.
     *  - [OrderMode.ORDER] / [OrderMode.SHUFFLE] / [OrderMode.NO_LOOP] → next, or `null` at the
     *    end (single pass, playback stops).
     *
     * Returns `null` for an empty playlist.
     */
    fun onCompletion(
        size: Int,
        current: Int,
        mode: OrderMode,
    ): Int? {
        if (size <= 0) return null
        val cur = current.coerceIn(0, size - 1)
        return when (mode) {
            OrderMode.REPEAT_CURRENT -> cur
            OrderMode.LOOP_ALL -> (cur + 1) % size
            OrderMode.ORDER, OrderMode.SHUFFLE, OrderMode.NO_LOOP ->
                if (cur + 1 < size) cur + 1 else null
        }
    }

    /**
     * Manual **Next** button: always moves forward one, wrapping at the end, regardless of loop
     * mode (the user asked to move on). Returns `null` only for an empty playlist.
     */
    fun manualNext(
        size: Int,
        current: Int,
    ): Int? {
        if (size <= 0) return null
        val cur = current.coerceIn(0, size - 1)
        return (cur + 1) % size
    }

    /**
     * Manual **Previous** button: always moves back one, wrapping at the start. Returns `null`
     * only for an empty playlist.
     */
    fun manualPrev(
        size: Int,
        current: Int,
    ): Int? {
        if (size <= 0) return null
        val cur = current.coerceIn(0, size - 1)
        return (cur - 1 + size) % size
    }

    /**
     * A deterministic Fisher-Yates permutation of `0 until size` for [OrderMode.SHUFFLE]. Seeded
     * so a given ([size], [seed]) always yields the same order — reproducible in tests and stable
     * across a config change without re-shuffling mid-playback.
     */
    fun shuffledOrder(
        size: Int,
        seed: Long,
    ): List<Int> {
        if (size <= 0) return emptyList()
        val order = MutableList(size) { it }
        val rng = Random(seed)
        for (i in size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        return order
    }
}
