package com.appblish.calculatorvault.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
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

// APP-293 item 14 — gallery-grade thumb geometry: a clearly grabbable pill (not a thin
// line), a wider invisible touch column, and the date bubble that rides beside it while
// dragging. All colors come from the Filora tokens (accent / onAccent / surface) — no
// ad-hoc hues in this file.
private val HANDLE_WIDTH = 8.dp
private val HANDLE_HEIGHT = 56.dp
private val TOUCH_WIDTH = 32.dp
private val BUBBLE_HEIGHT = 36.dp

/**
 * Samsung/Oppo/MIUI-style draggable fast-scroll for a lazy grid (P2-2 + APP-293 item 14):
 * a grabbable accent **pill handle** at the trailing edge that mirrors the scroll
 * position, jumps on drag (proportional [LazyGridState.scrollToItem]), fades out ~1s
 * after activity, and — while dragging — shows a **date/section bubble** beside the
 * handle naming where the drag is (supplied by [labelForIndex], which receives the
 * drag-target item index). Pure Compose — no library dependency. Renders nothing until
 * the container holds more than [minItemCount] cells, so short lists never grow a thumb.
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
    labelForIndex: ((Int) -> String?)? = null,
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
        labelForIndex = labelForIndex,
        modifier = modifier,
    )
}

/** [FastScrollbar] for a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn] — same thumb, list state. */
@Composable
fun FastScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    minItemCount: Int = FAST_SCROLL_MIN_ITEMS,
    labelForIndex: ((Int) -> String?)? = null,
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
        labelForIndex = labelForIndex,
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
    labelForIndex: ((Int) -> String?)?,
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
    val spacing = VaultTheme.spacing
    val scope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableIntStateOf(0) }
    val dragIndex = (dragFraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
    val bubbleLabel = if (dragging) labelForIndex?.invoke(dragIndex)?.takeIf { it.isNotBlank() } else null

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .wrapContentWidth(Alignment.End)
                .graphicsLayer { alpha = barAlpha }
                .onSizeChanged { trackHeightPx = it.height },
    ) {
        // The date/section bubble rides beside the handle while dragging (item 14):
        // accent container, on-accent label — where the drag will land.
        if (bubbleLabel != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            val travel = (trackHeightPx - HANDLE_HEIGHT.roundToPx()).coerceAtLeast(0)
                            val handleCenter = (fraction * travel + HANDLE_HEIGHT.toPx() / 2f).roundToInt()
                            IntOffset(x = 0, y = (handleCenter - BUBBLE_HEIGHT.roundToPx() / 2).coerceAtLeast(0))
                        }.padding(end = TOUCH_WIDTH + spacing.xs)
                        .height(BUBBLE_HEIGHT)
                        .background(colors.accent, VaultTheme.shapes.pill)
                        .padding(horizontal = spacing.md)
                        .testTag("fast-scroll-bubble"),
            ) {
                Text(
                    text = bubbleLabel,
                    style = VaultTheme.typography.labelLarge,
                    color = colors.onAccent,
                    maxLines = 1,
                )
            }
        }
        // The grabbable touch column: wider than the pill so the handle is easy to catch.
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(TOUCH_WIDTH)
                    .pointerInput(target, total) {
                        val handlePx = HANDLE_HEIGHT.toPx()

                        fun fractionAt(y: Float): Float {
                            val travel = (size.height - handlePx).coerceAtLeast(1f)
                            return ((y - handlePx / 2f) / travel).coerceIn(0f, 1f)
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
            // The pill handle (item 14): a clearly grabbable rounded handle in the accent
            // token — replaces the old 4dp line; no track behind it, gallery-style.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            val travel = (trackHeightPx - HANDLE_HEIGHT.roundToPx()).coerceAtLeast(0)
                            IntOffset(x = 0, y = (fraction * travel).roundToInt())
                        }.width(HANDLE_WIDTH)
                        .height(HANDLE_HEIGHT)
                        .background(colors.accent, VaultTheme.shapes.pill)
                        .testTag("fast-scroll-handle"),
            )
        }
    }
}
