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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.components.CategoryCard
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * The vault-home "CalcVault" dashboard (Vault tab). Large-title header with search +
 * settings, the five media categories with live counts, a cross-category Recent strip,
 * and an entry into the recycle bin. Matches the deck's home flow; the AppLock/Explore
 * tabs are siblings in [VaultShellScreen].
 */
@Composable
fun VaultHomeScreen(
    onCategoryClick: (VaultCategory) -> Unit,
    onRecentClick: (VaultItem) -> Unit,
    onRecycleBinClick: () -> Unit,
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
        HomeHeader(onSearch = { /* search flow — Phase 4 Explore */ }, onSettings = onSettingsClick)

        SectionLabel("Vault")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            contentPadding = PaddingValues(horizontal = spacing.lg),
        ) {
            items(VaultCategory.entries.toList()) { category ->
                CategoryCard(
                    label = category.label,
                    count = state.counts[category] ?: 0,
                    icon = category.icon(),
                    iconColor = category.color(),
                    onClick = { onCategoryClick(category) },
                    modifier = Modifier.width(132.dp),
                )
            }
        }

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

        Spacer(Modifier.height(spacing.lg))
        ListRow(
            title = "Recycle bin",
            subtitle = "Deleted items are kept 30 days",
            leadingIcon = Icons.Filled.Delete,
            leadingChipColor = colors.textSecondary,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onRecycleBinClick,
        )
    }
}

@Composable
private fun HomeHeader(
    onSearch: () -> Unit,
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
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = colors.textPrimary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = colors.textPrimary)
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
