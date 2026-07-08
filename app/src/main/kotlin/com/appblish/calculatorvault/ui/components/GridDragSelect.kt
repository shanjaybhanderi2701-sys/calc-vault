package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/** The three drag-select events a grid owner wires to its selection state (W1-E3). */
data class GridDragSelectCallbacks(
    val onDragStart: (itemId: String) -> Unit,
    val onDragOver: (itemId: String) -> Unit,
    val onDragEnd: () -> Unit,
)

/**
 * Long-press-drag range selection for a lazy grid (W1-E3, design S17). A long press
 * anchors on the pressed tile ([GridDragSelectCallbacks.onDragStart]); dragging then
 * reports every tile the pointer passes over ([GridDragSelectCallbacks.onDragOver]) so the
 * owner can select the anchor→current range. Nearing the top/bottom edge auto-scrolls the
 * grid so a drag can select past the viewport. Plain scrolls and taps are untouched — the
 * detector only claims the pointer after a long press, and tiles keep their own
 * tap/long-press handling (both fire on the anchor tile, idempotently).
 *
 * Only items keyed by a [String] are reported (media tiles); the grid's unkeyed
 * date-header rows fall through, so sweeping across a header just bridges two sections.
 */
fun Modifier.gridDragSelect(
    state: LazyGridState,
    callbacks: GridDragSelectCallbacks,
): Modifier =
    pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> itemIdAt(state, offset)?.let(callbacks.onDragStart) },
            onDrag = { change, _ ->
                autoScrollNearEdges(state, change.position.y, size.height)
                itemIdAt(state, change.position)?.let(callbacks.onDragOver)
            },
            onDragEnd = callbacks.onDragEnd,
            onDragCancel = callbacks.onDragEnd,
        )
    }

/** The String key of the visible grid item under [position], or null (header/gutter/none). */
private fun itemIdAt(
    state: LazyGridState,
    position: Offset,
): String? =
    state.layoutInfo.visibleItemsInfo
        .firstOrNull { info ->
            position.x >= info.offset.x &&
                position.x < info.offset.x + info.size.width &&
                position.y >= info.offset.y &&
                position.y < info.offset.y + info.size.height
        }?.key as? String

/**
 * Nudge the grid while the drag pointer sits in the top/bottom [EDGE_FRACTION] band so a
 * range select can extend beyond the viewport. `dispatchRawDelta` is synchronous and safe
 * from the pointer-input handler; the per-event step keeps the crawl controllable.
 */
private fun autoScrollNearEdges(
    state: LazyGridState,
    pointerY: Float,
    viewportHeight: Int,
) {
    val edge = viewportHeight * EDGE_FRACTION
    when {
        pointerY < edge -> state.dispatchRawDelta(-SCROLL_STEP_PX)
        pointerY > viewportHeight - edge -> state.dispatchRawDelta(SCROLL_STEP_PX)
    }
}

private const val EDGE_FRACTION = 0.12f
private const val SCROLL_STEP_PX = 24f
