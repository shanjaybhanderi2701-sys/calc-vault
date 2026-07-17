package com.appblish.calculatorvault.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon
import java.util.Locale

/**
 * The vault-home "CalcVault" dashboard — the root vault screen in Phase 1 (design call
 * D-1 on APP-224: no bottom-nav shell, no App Lock / Explore tabs). Large-title header
 * with the docx image27 icon trio **Search · Themes · Settings**, the three Phase-1 media
 * categories laid out as a **2×2 tile grid** (Photos · Videos / Audios · Bin) with dual
 * counts ("300 Photos / 8 Folders"), and a cross-category Recent strip showing real cover
 * thumbnails. The first-run hint is NOT drawn in-flow here: per APP-239 / APP-234 spec §0
 * the hint of record is APP-236's one-time anchored tooltip overlay (pref-gated), shipped
 * via APP-235 — one hint design only. There is no security banner: All Files Access is
 * primed contextually via the D-2 bottom sheet.
 */
@Composable
fun VaultHomeScreen(
    onCategoryClick: (VaultCategory) -> Unit,
    onRecentClick: (VaultItem) -> Unit,
    onRecycleBinClick: () -> Unit,
    onSearchClick: () -> Unit,
    onThemeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.canvas)
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.xxl),
    ) {
        HomeHeader(onSearch = onSearchClick, onTheme = onThemeClick, onSettings = onSettingsClick)

        CategoryGrid(
            state = state,
            onCategoryClick = onCategoryClick,
            onRecycleBinClick = onRecycleBinClick,
        )

        SectionLabel("Recent")
        if (state.recent.isEmpty()) {
            EmptyRecent()
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                contentPadding = PaddingValues(horizontal = spacing.lg),
            ) {
                items(state.recent, key = { it.id }) { item ->
                    RecentThumbnail(
                        item = item,
                        loadThumbnail = { viewModel.thumbnail(context, it) },
                        onClick = { onRecentClick(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onSearch: () -> Unit,
    onTheme: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.sm, top = spacing.xl, bottom = spacing.md),
    ) {
        Text(
            text = "CalcVault",
            style = VaultTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // Docx image27 header trio. The icon-switch action moved into Settings (S22).
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = colors.textPrimary)
        }
        IconButton(onClick = onTheme) {
            Icon(Icons.Filled.Star, contentDescription = "Themes", tint = colors.textPrimary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = colors.textPrimary)
        }
    }
}

/**
 * The [VaultCategory.HOME] categories (Photos · Videos · Audios · Documents) + the recycle
 * Bin, laid out as a 2-column grid of large rounded tiles — every tile visible without
 * horizontal scroll, each with a dual item/folder count subtitle. Built from plain Rows so
 * it composes inside the home's vertical scroll (no nested lazy grid). Documents joined the
 * grid with the APP-527 stream; Contacts stays omitted entirely — not teased (spec §0).
 */
@Composable
private fun CategoryGrid(
    state: VaultHomeState,
    onCategoryClick: (VaultCategory) -> Unit,
    onRecycleBinClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    val tiles =
        buildList {
            VaultCategory.HOME.forEach { category ->
                add(
                    HomeTile(
                        label = category.label,
                        subtitle =
                            categorySubtitle(
                                category,
                                items = state.counts[category] ?: 0,
                                folders = state.folderCounts[category] ?: 0,
                            ),
                        icon = category.icon(),
                        iconColor = category.color(),
                        onClick = { onCategoryClick(category) },
                    ),
                )
            }
            add(
                HomeTile(
                    label = "Bin",
                    subtitle = if (state.binCount == 0) "Empty" else pluralize(state.binCount, "items"),
                    icon = Icons.Filled.Delete,
                    iconColor = colors.destructive,
                    onClick = onRecycleBinClick,
                ),
            )
        }

    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.md),
        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
    ) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                rowTiles.forEach { tile ->
                    CategoryTile(tile = tile, modifier = Modifier.weight(1f))
                }
                // Keep a lone trailing tile at half width if the count is ever odd.
                if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class HomeTile(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconColor: Color,
    val onClick: () -> Unit,
)

@Composable
private fun CategoryTile(
    tile: HomeTile,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Surface(
        color = colors.surface,
        shape = VaultTheme.shapes.card,
        modifier = modifier.clip(VaultTheme.shapes.card).clickable(onClick = tile.onClick),
    ) {
        Column(modifier = Modifier.padding(spacing.lg).height(132.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tile.iconColor.copy(alpha = 0.16f)),
            ) {
                Icon(
                    imageVector = tile.icon,
                    contentDescription = null,
                    tint = tile.iconColor,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = tile.label,
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text = tile.subtitle,
                style = VaultTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/**
 * Deck copy: "300 Photos / 8 Albums", both nouns properly pluralized ("1 Album", never
 * "1 Albums" — APP-234 spec §1.3). Container noun is **"Albums"** — the W2-E §1
 * terminology lock (APP-218) deliberately supersedes the shipped "Folders" copy. Always
 * the category's own noun — docx image27's Videos tile subtitle reading "Photos" is a
 * docx-internal typo we do not copy (S8).
 */
internal fun categorySubtitle(
    category: VaultCategory,
    items: Int,
    folders: Int,
): String = "${pluralize(items, category.label)} / ${pluralize(folders, "Albums")}"

/**
 * "$count $pluralLabel" with the trailing "s" dropped when [count] == 1. Every label this
 * screen pluralizes is a regular plural ("Photos", "Videos", "Audios", "Albums", "items").
 */
internal fun pluralize(
    count: Int,
    pluralLabel: String,
): String = "$count ${if (count == 1) pluralLabel.dropLast(1) else pluralLabel}"

@Composable
private fun SectionLabel(text: String) {
    val spacing = VaultTheme.spacing
    Text(
        text = text,
        style = VaultTheme.typography.titleMedium,
        color = VaultTheme.colors.textPrimary,
        modifier = Modifier.padding(start = spacing.lg, top = spacing.lg, bottom = spacing.md),
    )
}

/**
 * One Recent-strip tile: the item's real cover decoded from its encrypted blob — the same
 * loader path as the category folder tiles (APP-234 spec §2.3) — with the APP-236 tile
 * spec's video treatment. Falls back to the category glyph while loading or for
 * non-visual types.
 */
@Composable
private fun RecentThumbnail(
    item: VaultItem,
    loadThumbnail: suspend (VaultItem) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    val cover: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = loadThumbnail(item)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(88.dp)
                .clip(VaultTheme.shapes.thumbnail)
                .background(colors.surfaceVariant)
                .clickable(onClick = onClick),
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.originalName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.category == VaultCategory.VIDEOS) {
                VideoCoverOverlay(durationMs = item.durationMs)
            }
        } else {
            Icon(
                imageVector = item.category.icon(),
                contentDescription = item.originalName,
                tint = item.category.color(),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Scrim ink for video-cover overlays — the APP-236 tile spec's #0D0F12. */
private val OverlayScrim = Color(0xFF0D0F12)

/**
 * Video cover treatment per the APP-236 tile spec (single source of truth; mirrored in
 * APP-234 spec §2.2): 40dp centered circular play badge (#0D0F12 @ 55%, 22dp glyph),
 * bottom-third gradient @ 45% for chip legibility, and a duration chip inset 6dp when
 * the duration is known.
 */
@Composable
private fun BoxScope.VideoCoverOverlay(durationMs: Long) {
    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(1f / 3f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to OverlayScrim.copy(alpha = 0.45f),
                    ),
                ),
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .align(Alignment.Center)
                .size(40.dp)
                .clip(CircleShape)
                .background(OverlayScrim.copy(alpha = 0.55f)),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
    if (durationMs > 0) {
        Text(
            text = formatDuration(durationMs),
            style = VaultTheme.typography.labelMedium,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OverlayScrim.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** "m:ss" (or "h:mm:ss" past an hour) for the video duration chip. */
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

@Composable
private fun EmptyRecent() {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Surface(
        color = colors.surface,
        shape = VaultTheme.shapes.card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg),
    ) {
        Text(
            text = "Nothing hidden yet. Tap a category, then + to hide your first item.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(spacing.lg),
        )
    }
}
