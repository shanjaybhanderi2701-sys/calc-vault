package com.appblish.calculatorvault.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The Explore tab — a single scrollable list of the Quick Tools, each a [ListRow] with a
 * green icon chip, title, one-line purpose, and disclosure chevron. Tapping a row pushes
 * the tool's full screen onto the nav graph. This is the hub the deck's Epic H flows all
 * hang off.
 */
@Composable
fun ExploreScreen(
    onToolClick: (ExploreTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Text(
            text = "Explore",
            style = VaultTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = spacing.lg, top = spacing.xl, bottom = spacing.xs),
        )
        Text(
            text = "Quick tools that live behind the vault.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = spacing.lg, bottom = spacing.md),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(ExploreTool.entries, key = { it.name }) { tool ->
                ListRow(
                    title = tool.title,
                    subtitle = tool.subtitle,
                    leadingIcon = tool.icon,
                    leadingChipColor = colors.accent,
                    trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
                    onClick = { onToolClick(tool) },
                )
            }
        }
    }
}

/** Small centered helper reused by tool screens for their warm empty states. */
@Composable
internal fun ToolEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.xxl),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = VaultTheme.typography.titleMedium, color = colors.textPrimary)
        Text(
            text = message,
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}
