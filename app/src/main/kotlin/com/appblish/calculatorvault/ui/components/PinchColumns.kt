package com.appblish.calculatorvault.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Two-finger pinch-to-change-columns for the vault's lazy grids (APP-293 item 10 — every
 * grid, Samsung/Oppo/MIUI-smooth).
 *
 * The feel contract is **fluid rescale during the pinch, no jumpy snap**, which a bare
 * `columns = n` flip cannot deliver. The trick: while two fingers are down the whole grid
 * scales visually with the pinch ([visualScale] via `graphicsLayer`); when the accumulated
 * scale crosses the geometric midpoint between two column counts, [columns] flips **and
 * the visual scale is compensated by the exact layout ratio** — so the on-screen tile size
 * is continuous through the reflow. Releasing the fingers springs the residual scale back
 * to 1, landing the grid crisply on the new column count.
 *
 * One-finger gestures are untouched (scroll/drag-select keep working); the moment a second
 * finger lands, the pinch owns the gesture and the events are consumed so the grid doesn't
 * scroll underneath.
 */
@Stable
class PinchColumnsState internal constructor(
    initialColumns: Int,
    val minColumns: Int,
    val maxColumns: Int,
    private val scope: CoroutineScope,
) {
    /** Live column count for `GridCells.Fixed` — flips mid-pinch at scale midpoints. */
    var columns by mutableIntStateOf(initialColumns.coerceIn(minColumns, maxColumns))
        private set

    /** The transient pinch scale the grid renders through; exactly 1 at rest. */
    var visualScale by mutableFloatStateOf(1f)
        private set

    private var settleJob: Job? = null

    internal fun onPinchStart() {
        settleJob?.cancel()
    }

    internal fun onPinch(zoom: Float) {
        if (zoom <= 0f) return
        visualScale *= zoom
        // Fewer columns = bigger tiles: crossing √(c/(c-1)) flips the layout and divides
        // the scale by the same ratio the tiles just grew by — visually continuous.
        while (columns > minColumns && visualScale >= midpoint(columns, columns - 1)) {
            visualScale *= (columns - 1).toFloat() / columns
            columns -= 1
        }
        // More columns = smaller tiles, mirrored math on the pinch-in side.
        while (columns < maxColumns && visualScale <= 1f / midpoint(columns + 1, columns)) {
            visualScale *= (columns + 1).toFloat() / columns
            columns += 1
        }
    }

    internal fun onPinchEnd() {
        settleJob?.cancel()
        settleJob =
            scope.launch {
                val start = visualScale
                if (start == 1f) return@launch
                animate(
                    initialValue = start,
                    targetValue = 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ) { value, _ -> visualScale = value }
            }
    }

    private fun midpoint(
        from: Int,
        to: Int,
    ): Float = sqrt(from.toFloat() / to)
}

@Composable
fun rememberPinchColumnsState(
    initialColumns: Int = 3,
    minColumns: Int = 2,
    // APP-314 item 3: the vault grids pinch from a dense 6 up to a roomy 2 (was capped at 5).
    maxColumns: Int = 6,
): PinchColumnsState {
    val scope = rememberCoroutineScope()
    return remember { PinchColumnsState(initialColumns, minColumns, maxColumns, scope) }
}

/**
 * Wire a grid to [state]: intercepts two-finger pinches (single-finger scroll passes
 * through untouched) and renders the transient pinch scale. Apply to the grid composable
 * itself; pair with `columns = GridCells.Fixed(state.columns)`.
 */
fun Modifier.pinchColumns(state: PinchColumnsState): Modifier =
    this
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var pinched = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressedChanges = event.changes.filter { it.pressed }
                    if (pressedChanges.isEmpty()) break
                    if (pressedChanges.size >= 2) {
                        if (!pinched) {
                            pinched = true
                            state.onPinchStart()
                        }
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) state.onPinch(zoom)
                        // The pinch owns the gesture: stop the grid scrolling underneath.
                        event.changes.forEach { it.consume() }
                    }
                }
                if (pinched) state.onPinchEnd()
            }
        }.graphicsLayer {
            scaleX = state.visualScale
            scaleY = state.visualScale
            // Anchor near the viewport's upper middle — tiles appear to grow/shrink in
            // place under the fingers rather than sliding away from a corner.
            transformOrigin = TransformOrigin(0.5f, 0.3f)
        }
