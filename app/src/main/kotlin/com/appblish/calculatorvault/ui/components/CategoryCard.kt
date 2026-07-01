package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * A media-vault category tile (Photos / Videos / Audios / Files / Contacts) from the
 * dashboard: colored rounded icon-chip, label, and item count. Sizes to its content;
 * arrange several in a Row or a grid.
 */
@Composable
fun CategoryCard(
    label: String,
    count: Int,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Surface(
        color = colors.surface,
        shape = VaultTheme.shapes.card,
        modifier = modifier.clip(VaultTheme.shapes.card).clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconColor.copy(alpha = 0.16f)),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                text = label,
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = spacing.md),
            )
            Text(
                text = if (count == 1) "1 item" else "$count items",
                style = VaultTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}
