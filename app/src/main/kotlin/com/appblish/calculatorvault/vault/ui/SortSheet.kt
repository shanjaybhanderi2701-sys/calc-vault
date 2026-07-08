package com.appblish.calculatorvault.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultGridTokens
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.GridSort
import com.appblish.calculatorvault.vault.model.SortDirection
import com.appblish.calculatorvault.vault.model.SortKey

/**
 * The W3-E "Sort by" bottom sheet (design W3-D §7): radio rows for [keys], the
 * Ascending/Descending segmented pill, and — on the photo-grid sheet only — the
 * "This album only" per-album-override checkbox (design G-8).
 *
 * **Live application, no confirm button** (design G-6): every tap re-sorts the grid
 * behind the sheet immediately via [onSortChange]; the sheet stays up for key/direction
 * combos and dismisses on scrim-tap/swipe. Re-selecting the active key or direction is a
 * no-op (the callback simply re-emits the same persisted value — no write churn upstream).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(
    keys: List<SortKey>,
    current: GridSort,
    onSortChange: (GridSort) -> Unit,
    onDismiss: () -> Unit,
    thisAlbumOnly: Boolean? = null,
    onThisAlbumOnlyChange: (Boolean) -> Unit = {},
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        modifier = Modifier.testTag("sort-sheet"),
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) {
            Text(
                text = "Sort by",
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            keys.forEach { key ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(VaultGridTokens.MenuRowHeight)
                            .clickable { if (key != current.key) onSortChange(current.copy(key = key)) }
                            .testTag("sort-key-${key.name}"),
                ) {
                    RadioButton(
                        selected = key == current.key,
                        onClick = { if (key != current.key) onSortChange(current.copy(key = key)) },
                    )
                    Text(
                        text = key.label,
                        style = VaultTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(start = spacing.sm),
                    )
                }
            }
            DirectionToggle(
                current = current.direction,
                onSelect = { if (it != current.direction) onSortChange(current.copy(direction = it)) },
                modifier = Modifier.padding(vertical = spacing.md),
            )
            if (thisAlbumOnly != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(VaultGridTokens.MenuRowHeight)
                            .clickable { onThisAlbumOnlyChange(!thisAlbumOnly) }
                            .testTag("sort-this-album-only"),
                ) {
                    Checkbox(checked = thisAlbumOnly, onCheckedChange = onThisAlbumOnlyChange)
                    Text(
                        text = "This album only",
                        style = VaultTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(start = spacing.sm),
                    )
                }
            }
        }
    }
}

/** control.segmented — the 2-segment Ascending/Descending pill (W3-D §3). */
@Composable
private fun DirectionToggle(
    current: SortDirection,
    onSelect: (SortDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(VaultGridTokens.SegmentedHeight)
                .clip(VaultTheme.shapes.card)
                .background(colors.surfaceVariant),
    ) {
        SortDirection.entries.forEach { direction ->
            val selected = direction == current
            Text(
                text = direction.label,
                style = VaultTheme.typography.labelLarge,
                color = if (selected) VaultGridTokens.SegmentedSelectedLabel else colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(VaultTheme.shapes.card)
                        .background(
                            if (selected) VaultGridTokens.SegmentedSelectedContainer else colors.surfaceVariant,
                        ).clickable { onSelect(direction) }
                        .padding(vertical = 10.dp)
                        .testTag("sort-direction-${direction.name}"),
            )
        }
    }
}
