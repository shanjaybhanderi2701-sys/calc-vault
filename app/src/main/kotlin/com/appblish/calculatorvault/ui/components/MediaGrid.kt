package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
 * Thumbnails render as neutral placeholders until the encrypted media store lands.
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
) {
    val spacing = VaultTheme.spacing
    val groups = groupMediaByDate(items)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.lg),
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
                    onLongPress = { onItemLongPress(item) },
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
    onLongPress: () -> Unit,
) {
    val colors = VaultTheme.colors
    Box(
        modifier =
            Modifier
                .padding(2.dp)
                .aspectRatio(1f)
                .clip(VaultTheme.shapes.thumbnail)
                .background(colors.surfaceVariant)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
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
