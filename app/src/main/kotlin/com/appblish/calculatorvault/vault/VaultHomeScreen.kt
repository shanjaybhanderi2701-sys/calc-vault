package com.appblish.calculatorvault.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * The vault-home "CalcVault" dashboard — the root vault screen in Phase 1 (design call
 * D-1 on APP-224: no bottom-nav shell, no App Lock / Explore tabs). Large-title header
 * with the docx image27 icon trio **Search · Themes · Settings**, the three Phase-1 media
 * categories laid out as a **2×2 tile grid** (Photos · Videos / Audios · Bin) with dual
 * counts ("300 Photos / 8 Folders"), and a cross-category Recent strip. On first run
 * (empty vault) a "Hide Photos Here" coach-mark points at the Photos tile. There is no
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
                    RecentThumbnail(item = item, onClick = { onRecentClick(item) })
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
                    subtitle = if (state.binCount == 0) "Empty" else pluralItems(state.binCount),
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
        // First-run hint pointing at the Photos tile (top-left of the grid).
        if (state.isEmpty) {
            CoachMark(text = "Hide Photos Here")
        }
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
 * Deck copy: "300 Photos / 8 Folders". Always the category's own noun — docx image27's
 * Videos tile subtitle reading "Photos" is a docx-internal typo we do not copy (S8).
 */
private fun categorySubtitle(
    category: VaultCategory,
    items: Int,
    folders: Int,
): String = "$items ${category.label} / $folders Folders"

private fun pluralItems(count: Int): String = if (count == 1) "1 item" else "$count items"

/** A small first-run tooltip pill with a downward caret, sitting above the target tile. */
@Composable
private fun CoachMark(text: String) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(horizontalAlignment = Alignment.Start) {
        Surface(color = colors.accent, shape = VaultTheme.shapes.pill) {
            Text(
                text = text,
                style = VaultTheme.typography.labelLarge,
                color = colors.onAccent,
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            )
        }
        Box(
            modifier =
                Modifier
                    .padding(start = spacing.xl)
                    .size(width = 12.dp, height = 6.dp)
                    .background(colors.accent),
        )
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

@Composable
private fun RecentThumbnail(
    item: VaultItem,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(88.dp)
                .clip(VaultTheme.shapes.thumbnail)
                .background(colors.surfaceVariant)
                .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = item.category.icon(),
            contentDescription = item.originalName,
            tint = item.category.color(),
            modifier = Modifier.size(28.dp),
        )
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
