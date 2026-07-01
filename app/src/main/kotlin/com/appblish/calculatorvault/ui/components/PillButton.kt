package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** Visual role of a [PillButton] — matches the deck's CTA hierarchy. */
enum class PillButtonStyle {
    /** Green filled — the one primary action on a screen. */
    Primary,

    /** Outlined neutral — secondary / cancel. */
    Secondary,

    /** Red filled — destructive confirm (delete, permanent remove). */
    Destructive,
}

/**
 * Full-width pill CTA from the deck. One [Primary] per screen; [Destructive] only
 * for irreversible actions. Optional [leadingIcon] renders before the label.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillButtonStyle = PillButtonStyle.Primary,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colors = VaultTheme.colors
    val shape = VaultTheme.shapes.pill
    val contentPadding = PaddingValues(horizontal = VaultTheme.spacing.xl, vertical = VaultTheme.spacing.md)

    if (style == PillButtonStyle.Secondary) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.textPrimary,
                ),
            modifier = modifier.fillMaxWidth().heightIn(min = VaultTheme.spacing.touchTarget),
        ) {
            PillContent(text = text, leadingIcon = leadingIcon)
        }
        return
    }

    val container = if (style == PillButtonStyle.Destructive) colors.destructive else colors.accent
    val onContainer = if (style == PillButtonStyle.Destructive) colors.onDestructive else colors.onAccent
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        contentPadding = contentPadding,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = onContainer,
                disabledContainerColor = colors.surfaceVariant,
                disabledContentColor = colors.textDisabled,
            ),
        modifier = modifier.fillMaxWidth().heightIn(min = VaultTheme.spacing.touchTarget),
    ) {
        PillContent(text = text, leadingIcon = leadingIcon)
    }
}

@Composable
private fun PillContent(
    text: String,
    leadingIcon: ImageVector?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp).padding(end = VaultTheme.spacing.xs),
            )
        }
        Text(text = text, style = VaultTheme.typography.titleMedium)
    }
}
