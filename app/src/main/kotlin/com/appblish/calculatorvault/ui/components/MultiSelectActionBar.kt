package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** One action in the [MultiSelectActionBar] (share, move, unhide, delete…). */
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** One row inside the selection bar's `⋯` overflow menu (W3-E: Rename / Pin / Set as cover). */
data class SelectionOverflowItem(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The contextual action bar shown while multi-selecting media/files ("pinch" flows).
 * Leading close button clears the selection; the count reads "N selected"; trailing
 * icon-actions operate on the selection, with delete tinted destructive-red. A non-empty
 * [overflow] adds a trailing `⋯` button opening a menu of identity-level actions — the
 * W3-D §4/§5 pattern for N=1-only items; callers pass an empty list to hide it entirely.
 */
@Composable
fun MultiSelectActionBar(
    selectedCount: Int,
    closeIcon: ImageVector,
    onClose: () -> Unit,
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
    overflow: List<SelectionOverflowItem> = emptyList(),
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Surface(color = colors.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = closeIcon,
                    contentDescription = "Clear selection",
                    tint = colors.textPrimary,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = "$selectedCount selected",
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                actions.forEach { action ->
                    IconButton(onClick = action.onClick) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.label,
                            tint = if (action.destructive) colors.destructive else colors.textPrimary,
                        )
                    }
                }
                if (overflow.isNotEmpty()) {
                    SelectionOverflowMenu(items = overflow)
                }
            }
        }
    }
}

@Composable
private fun SelectionOverflowMenu(items: List<SelectionOverflowItem>) {
    val colors = VaultTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = colors.textPrimary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, color = colors.textPrimary) },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                )
            }
        }
    }
}
