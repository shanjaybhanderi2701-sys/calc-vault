package com.appblish.calculatorvault.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * thumbnails. On first run (nothing hidden yet) a coach bubble anchored to the Photos
 * tile — "Tap Photos to hide your first photo" — is the screen's single hint; the Recent
 * section is omitted entirely until content exists (APP-234 spec §1.1). There is no
 * security banner: All Files Access is primed contextually via the D-2 bottom sheet.
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

        // While the vault is empty the coach bubble is the single first-run hint — an
        // orphaned "Recent" header (or an empty-state card) would compete with it.
        if (state.recent.isNotEmpty()) {
            SectionLabel("Recent")
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
 * The three Phase-1 media categories + the recycle Bin, laid out as a 2×2 grid of large
 * rounded tiles (Photos·Videos / Audios·Bin) — every tile visible without horizontal
 * scroll, each with a dual item/folder count subtitle. Built from plain Rows so it
 * composes inside the home's vertical scroll (no nested lazy grid). Documents and
 * Contacts are omitted entirely — not teased (spec §0, design call D-1).
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
            VaultCategory.PHASE1.forEach { category ->
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

    Column(modifier = Modifier.padding(horizontal = spacing.lg)) {
        // First-run coach bubble anchored to the Photos tile (top-left of the grid).
        // Tapping it acts as tapping the tile itself (APP-234 spec §1.1).
        FirstRunCoachBubble(
            visible = state.isEmpty,
            onClick = { onCategoryClick(VaultCategory.PHOTOS) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            modifier =
                Modifier.padding(
                    // The bubble's caret supplies the 8dp gap to the Photos tile when
                    // visible; otherwise keep the grid's usual rhythm under the header.
                    top = if (state.isEmpty) 0.dp else spacing.md,
                    bottom = spacing.md,
                ),
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
 * Deck copy: "300 Photos / 8 Folders", both nouns properly pluralized ("1 Folder", never
 * "1 Folders" — APP-234 spec §1.3). Always the category's own noun — docx image27's
 * Videos tile subtitle reading "Photos" is a docx-internal typo we do not copy (S8).
 */
internal fun categorySubtitle(
    category: VaultCategory,
    items: Int,
    folders: Int,
): String = "${pluralize(items, category.label)} / ${pluralize(folders, "Folders")}"

/**
 * "$count $pluralLabel" with the trailing "s" dropped when [count] == 1. Every label this
 * screen pluralizes is a regular plural ("Photos", "Videos", "Audios", "Folders", "items").
 */
internal fun pluralize(
    count: Int,
    pluralLabel: String,
): String = "$count ${if (count == 1) pluralLabel.dropLast(1) else pluralLabel}"

/** The single first-run hint's copy — also the bubble group's contentDescription. */
private const val FIRST_RUN_HINT = "Tap Photos to hide your first photo"

/** Appear/disappear duration for the first-run bubble (spec §1.1: 200ms, standard easing). */
private const val HINT_MOTION_MS = 200

/**
 * The first-run coach bubble (APP-234 spec §1.1): an accent rounded rect with a true
 * 16×8dp triangular caret flush against its bottom edge, the caret's center-x sitting on
 * the center of the Photos tile column (half of the first of two grid columns split by
 * one [VaultTheme.spacing.md] gap). Fades/slides in 8dp on appear and fades out on the
 * first hide; tapping anywhere on the group navigates to Photos.
 */
@Composable
private fun FirstRunCoachBubble(
    visible: Boolean,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(animationSpec = tween(HINT_MOTION_MS)) +
                slideInVertically(animationSpec = tween(HINT_MOTION_MS)) {
                    with(density) { -spacing.sm.roundToPx() }
                },
        exit = fadeOut(animationSpec = tween(HINT_MOTION_MS)),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs, bottom = spacing.sm),
        ) {
            val caretCenterX = (maxWidth - spacing.md) / 4
            val accent = colors.accent
            Column(
                modifier =
                    Modifier
                        .heightIn(min = 40.dp)
                        .clickable(onClick = onClick)
                        .semantics(mergeDescendants = true) { contentDescription = FIRST_RUN_HINT },
            ) {
                Surface(color = accent, shape = VaultTheme.shapes.thumbnail) {
                    Text(
                        text = FIRST_RUN_HINT,
                        style = VaultTheme.typography.labelLarge,
                        color = colors.onAccent,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = 10.dp),
                    )
                }
                // True triangle caret (three-point path), flush to the bubble — zero gap.
                Canvas(
                    modifier =
                        Modifier
                            .padding(start = caretCenterX - 8.dp)
                            .size(width = 16.dp, height = 8.dp),
                ) {
                    val caret =
                        Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width / 2f, size.height)
                            close()
                        }
                    drawPath(caret, color = accent)
                }
            }
        }
    }
}

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
 * loader path as the category folder tiles — with the §2.2 play overlay + duration badge
 * on videos. Falls back to the category glyph while loading or for non-visual types.
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
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * Video cover treatment (APP-234 spec §2.2): a centered white play triangle inside a
 * 40dp circular 40% black scrim, plus a duration badge bottom-right (white labelMedium
 * on 55% black, 6dp radius, 6/2dp padding, 6dp from the tile edges) when known.
 */
@Composable
private fun BoxScope.VideoCoverOverlay(durationMs: Long) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .align(Alignment.Center)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f)),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
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
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** "m:ss" (or "h:mm:ss" past an hour) for the video duration badge. */
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
