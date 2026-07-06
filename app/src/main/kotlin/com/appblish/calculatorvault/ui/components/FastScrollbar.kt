package com.appblish.calculatorvault.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Hide the scrollbar this long after the last scroll/drag activity (P2-2, board feedback). */
private const val HIDE_DELAY_MS = 1_000L

/** Below this many lazy cells the container is short enough that fast-scroll is noise. */
private const val FAST_SCROLL_MIN_ITEMS = 30

private val THUMB_WIDTH = 4.dp
private val THUMB_HEIGHT = 48.dp

/** Touch width of the drag target — wider than the 4dp visual so the thumb is grabbable. */
private val TOUCH_WIDTH = 16.dp

/**
 * A minimal draggable fast-scroll thumb for a lazy grid (P2-2, APP-225 board feedback):
 * a rounded 4dp accent thumb on a low-alpha track at the right edge that mirrors the
 * grid's scroll position, jumps on drag (proportional [LazyGridState.scrollToItem]), and
 * fades out ~1s after scrolling stops. Pure Compose — no library dependency. Renders
 * nothing until the grid holds more than [minItemCount] cells, so short lists never grow
 * a scrollbar.
 *
 * Overlay it on the grid's parent `Box`, aligned to the trailing edge:
 * ```
 * Box(modifier) {
 *     LazyVerticalGrid(state = gridState, …)
 *     FastScrollbar(state = gridState, modifier = Modifier.align(Alignment.CenterEnd))
 * }
 * ```
 */
@Composable
fun FastScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    minItemCount: Int = FAST_SCROLL_MIN_ITEMS,
) {
    FastScrollbarImpl(
        target =
            remember(state) {
                FastScrollTarget(
                    totalItems = { state.layoutInfo.totalItemsCount },
                    firstVisibleIndex = { state.firstVisibleItemIndex },
                    visibleCount = { state.layoutInfo.visibleItemsInfo.size },
                    isScrolling = { state.isScrollInProgress },
                    scrollToItem = { state.scrollToItem(it) },
                )
            },
        minItemCount = minItemCount,
        modifier = modifier,
    )
}

/** [FastScrollbar] for a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn] — same thumb, list state. */
@Composable
fun FastScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    minItemCount: Int = FAST_SCROLL_MIN_ITEMS,
) {
    FastScrollbarImpl(
        target =
            remember(state) {
                FastScrollTarget(
                    totalItems = { state.layoutInfo.totalItemsCount },
                    firstVisibleIndex = { state.firstVisibleItemIndex },
                    visibleCount = { state.layoutInfo.visibleItemsInfo.size },
                    isScrolling = { state.isScrollInProgress },
                    scrollToItem = { state.scrollToItem(it) },
                )
            },
        minItemCount = minItemCount,
        modifier = modifier,
    )
}

/**
 * Container-agnostic view of a lazy list/grid, so one thumb implementation serves both
 * ([LazyGridState] and [LazyListState] share no scroll interface). The lambdas read
 * snapshot state, so composables reading through them recompose on scroll as usual.
 */
private class FastScrollTarget(
    val totalItems: () -> Int,
    val firstVisibleIndex: () -> Int,
    val visibleCount: () -> Int,
    val isScrolling: () -> Boolean,
    val scrollToItem: suspend (Int) -> Unit,
)

@Composable
private fun FastScrollbarImpl(
    target: FastScrollTarget,
    minItemCount: Int,
    modifier: Modifier = Modifier,
) {
    val total = target.totalItems()
    if (total <= minItemCount) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = target.isScrolling()

    // Auto-hide: appear on any scroll/drag activity, fade out HIDE_DELAY_MS after rest.
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            visible = true
        } else if (visible) {
            delay(HIDE_DELAY_MS)
            visible = false
        }
    }
    val barAlpha by animateFloatAsState(targetValue = if (visible) 1f else 0f, label = "fastScrollbarAlpha")
    if (barAlpha <= 0f) return

    // While dragging the thumb leads (the grid follows via scrollToItem); otherwise the
    // thumb mirrors the scroll position as first-visible over the max reachable index.
    val fraction =
        if (dragging) {
            dragFraction
        } else {
            val maxIndex = (total - target.visibleCount()).coerceAtLeast(1)
            (target.firstVisibleIndex().toFloat() / maxIndex).coerceIn(0f, 1f)
        }

    val colors = VaultTheme.colors
    val scope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .width(TOUCH_WIDTH)
                .graphicsLayer { alpha = barAlpha }
                .onSizeChanged { trackHeightPx = it.height }
                .pointerInput(target, total) {
                    val thumbPx = THUMB_HEIGHT.toPx()

                    fun fractionAt(y: Float): Float {
                        val travel = (size.height - thumbPx).coerceAtLeast(1f)
                        return ((y - thumbPx / 2f) / travel).coerceIn(0f, 1f)
                    }

                    fun jumpTo(value: Float) {
                        scope.launch { target.scrollToItem((value * (total - 1)).roundToInt()) }
                    }
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = fractionAt(offset.y)
                            jumpTo(dragFraction)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = fractionAt(change.position.y)
                        jumpTo(dragFraction)
                    }
                },
    ) {
        // Low-alpha full-height track behind the thumb.
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .width(THUMB_WIDTH)
                    .background(colors.accent.copy(alpha = 0.12f), VaultTheme.shapes.pill),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        val travel = (trackHeightPx - THUMB_HEIGHT.roundToPx()).coerceAtLeast(0)
                        IntOffset(x = 0, y = (fraction * travel).roundToInt())
                    }.width(THUMB_WIDTH)
                    .height(THUMB_HEIGHT)
                    .background(colors.accent, VaultTheme.shapes.pill),
        )
    }
}
