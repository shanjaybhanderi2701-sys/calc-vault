package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** Trailing affordance for a [ListRow] — chevron (navigates), toggle, or nothing. */
sealed interface RowTrailing {
    /** A chevron / disclosure indicator. */
    data class Chevron(
        val icon: ImageVector,
    ) : RowTrailing

    /** A lock/enable switch (App Lock rows). */
    data class Toggle(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : RowTrailing

    /** A short count / status badge. */
    data class Badge(
        val label: String,
    ) : RowTrailing

    /** No trailing element. */
    data object None : RowTrailing
}

/**
 * The recurring list row from the deck: leading colored icon chip, title + optional
 * subtitle, and a trailing chevron / toggle / badge. Used by App Lock, settings,
 * folder lists, contacts.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingChipColor: Color? = null,
    trailing: RowTrailing = RowTrailing.None,
    onClick: (() -> Unit)? = null,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val base =
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = spacing.lg, vertical = spacing.md)

    Row(modifier = base, verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) {
            val chip = leadingChipColor ?: colors.accent
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(chip.copy(alpha = 0.16f)),
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = chip,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(spacing.md))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = VaultTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = VaultTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(spacing.sm))
        RowTrailingContent(trailing)
    }
}

@Composable
private fun RowTrailingContent(trailing: RowTrailing) {
    val colors = VaultTheme.colors
    when (trailing) {
        is RowTrailing.Chevron ->
            Icon(
                imageVector = trailing.icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )

        is RowTrailing.Toggle ->
            Switch(
                checked = trailing.checked,
                onCheckedChange = trailing.onCheckedChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccent,
                        checkedTrackColor = colors.accent,
                        uncheckedTrackColor = colors.surfaceVariant,
                        uncheckedBorderColor = colors.divider,
                    ),
            )

        is RowTrailing.Badge ->
            Text(
                text = trailing.label,
                style = VaultTheme.typography.labelLarge,
                color = colors.textSecondary,
            )

        RowTrailing.None -> Unit
    }
}
