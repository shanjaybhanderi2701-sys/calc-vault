package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** One action in the [MultiSelectActionBar] (share, move, unhide, delete…). */
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** One row inside a selection `⋯` menu (Rename / Pin / Change cover photo / Property). */
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

/**
 * The multi-select **bottom action tray** (APP-293 item 13): every action as an
 * icon-over-label column — the viewer bottom-bar pattern — with the primaries up front
 * and everything else inside a trailing `More` menu. Overlay it at the screen's bottom
 * edge while a selection is live; the count/Select-all top bar stays separate.
 */
@Composable
fun SelectionActionTray(
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
    overflow: List<SelectionOverflowItem> = emptyList(),
) {
    val colors = VaultTheme.colors
    var moreOpen by remember { mutableStateOf(false) }
    Surface(color = colors.surfaceVariant, shadowElevation = 6.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = VaultTheme.spacing.xs),
        ) {
            actions.forEach { action ->
                TrayAction(
                    label = action.label,
                    icon = action.icon,
                    tint = if (action.destructive) colors.destructive else colors.textPrimary,
                    onClick = action.onClick,
                )
            }
            if (overflow.isNotEmpty()) {
                Box {
                    TrayAction(
                        label = "More",
                        icon = Icons.Filled.MoreVert,
                        tint = colors.textPrimary,
                        onClick = { moreOpen = true },
                        contentDescription = "More options",
                    )
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        overflow.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label, color = colors.textPrimary) },
                                onClick = {
                                    moreOpen = false
                                    item.onClick()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One icon-over-label tray target (mirrors the viewer's `ViewerAction`). */
@Composable
private fun TrayAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    contentDescription: String = label,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .clip(VaultTheme.shapes.card)
                .clickable(onClick = onClick)
                .padding(horizontal = VaultTheme.spacing.sm, vertical = VaultTheme.spacing.xs),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
        Text(text = label, style = VaultTheme.typography.labelMedium, color = tint)
    }
}
