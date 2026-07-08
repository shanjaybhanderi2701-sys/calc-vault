package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** A single hidden media/file item. `sortKey` is a recency key (e.g. epoch millis). */
data class MediaItem(
    val id: String,
    val dateLabel: String,
    val sortKey: Long,
)

/** A date-header section: a label ("Today", "12 Jun 2026") and its items. */
data class MediaGroup(
    val dateLabel: String,
    val items: List<MediaItem>,
)

/**
 * Groups media into date sections, newest first. Pure (no Compose) so the grouping
 * is unit-testable: sections are ordered by their items' max [MediaItem.sortKey]
 * descending, and items within a section by [MediaItem.sortKey] descending.
 */
fun groupMediaByDate(items: List<MediaItem>): List<MediaGroup> =
    items
        .groupBy { it.dateLabel }
        .map { (label, group) -> MediaGroup(label, group.sortedByDescending { it.sortKey }) }
        .sortedByDescending { group -> group.items.maxOf { it.sortKey } }

/**
 * The date-grouped media grid from the deck: square thumbnails under sticky-style
 * date headers, with a selection-check overlay in multi-select ("pinch") mode.
 *
 * When [loadThumbnail] is supplied, each tile lazily decodes a real preview for its item
 * (a decrypted image/video frame for hidden items, or a MediaStore thumbnail for picker
 * sources); tiles show the neutral placeholder while the load is in flight or returns
 * null. With no [loadThumbnail] (Compose previews / tests) every tile is a placeholder.
 *
 * With [dragSelect] wired, a long-press-drag range-selects the swept tiles (W1-E3) — the
 * handler is applied *inside* the grid's own padding so pointer positions line up with
 * [LazyGridState.layoutInfo] item coordinates. The grid-level detector then owns the
 * long-press entirely: its onDragStart fires on the press itself (movement or not), so
 * [onItemLongPress] is served through [GridDragSelectCallbacks.onDragStart] and the tiles
 * drop their own long-click handler — a tile-level long-press consumes the pointer until
 * up (tap-gesture semantics), which would cancel the grid's drag the moment it starts.
 */
@Composable
fun DateGroupedMediaGrid(
    items: List<MediaItem>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    checkIcon: ImageVector,
    onItemClick: (MediaItem) -> Unit,
    onItemLongPress: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    loadThumbnail: (suspend (MediaItem) -> ImageBitmap?)? = null,
    state: LazyGridState = rememberLazyGridState(),
    dragSelect: GridDragSelectCallbacks? = null,
) {
    val spacing = VaultTheme.spacing
    val groups = groupMediaByDate(items)
    val gridModifier = modifier.testTag("media-grid").fillMaxWidth().padding(horizontal = spacing.lg)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = if (dragSelect != null) gridModifier.gridDragSelect(state, dragSelect) else gridModifier,
    ) {
        groups.forEach { group ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = group.dateLabel,
                    style = VaultTheme.typography.labelLarge,
                    color = VaultTheme.colors.textSecondary,
                    modifier = Modifier.padding(vertical = spacing.md),
                )
            }
            items(group.items, key = { it.id }) { item ->
                MediaThumbnail(
                    item = item,
                    selected = item.id in selectedIds,
                    selectionMode = selectionMode,
                    checkIcon = checkIcon,
                    onClick = { onItemClick(item) },
                    // See the grid KDoc: with drag-select active the grid detector owns
                    // long-press; a tile-level long-click would cancel the drag.
                    onLongPress = if (dragSelect == null) ({ onItemLongPress(item) }) else null,
                    loadThumbnail = loadThumbnail,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(
    item: MediaItem,
    selected: Boolean,
    selectionMode: Boolean,
    checkIcon: ImageVector,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    loadThumbnail: (suspend (MediaItem) -> ImageBitmap?)? = null,
) {
    val colors = VaultTheme.colors
    val thumbnail: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = loadThumbnail?.invoke(item)
    }
    Box(
        modifier =
            Modifier
                .padding(2.dp)
                .aspectRatio(1f)
                .clip(VaultTheme.shapes.thumbnail)
                .background(colors.surfaceVariant)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .testTag("media-tile-${item.id}"),
    ) {
        thumbnail?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (selectionMode) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.accent else colors.canvas.copy(alpha = 0.5f)),
            ) {
                if (selected) {
                    Icon(
                        imageVector = checkIcon,
                        contentDescription = "Selected",
                        tint = colors.onAccent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(colors.accent.copy(alpha = 0.18f)),
            )
        }
    }
}
