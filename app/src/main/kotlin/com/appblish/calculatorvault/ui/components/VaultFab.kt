package com.appblish.calculatorvault.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** One entry in the FAB's expand menu (e.g. "Create Folder", "Hide Photos"). */
data class FabAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * The green FAB with an expand menu from the deck. Tapping the FAB flips [expanded]
 * (via [onExpandedChange]) to reveal labeled mini-actions stacked above it; the main
 * icon rotates 45° so a `+` reads as a close `×`.
 */
@Composable
fun VaultFab(
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: List<FabAction>,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
        modifier = modifier,
    ) {
        AnimatedVisibility(visible = expanded) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                actions.forEach { action ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = colors.surface,
                            shape = VaultTheme.shapes.chip,
                            shadowElevation = 2.dp,
                        ) {
                            Text(
                                text = action.label,
                                style = VaultTheme.typography.labelLarge,
                                color = colors.textPrimary,
                                modifier =
                                    Modifier.padding(
                                        horizontal = spacing.md,
                                        vertical = spacing.sm,
                                    ),
                            )
                        }
                        Spacer(Modifier.width(spacing.md))
                        SmallFloatingActionButton(
                            onClick = {
                                onExpandedChange(false)
                                action.onClick()
                            },
                            containerColor = colors.surfaceVariant,
                            contentColor = colors.accent,
                        ) {
                            Icon(imageVector = action.icon, contentDescription = action.label)
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = if (expanded) 45f else 0f },
            )
        }
    }
}
