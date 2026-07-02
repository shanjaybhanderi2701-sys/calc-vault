package com.appblish.calculatorvault.vault.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The recurring large-title header with a back chevron used by every pushed screen
 * (category, hide/import, viewers, recycle bin). Optional trailing action slot for a
 * gear / overflow. Back uses a left chevron — the Android-idiomatic translation of the
 * deck's iOS back affordance.
 */
@Composable
fun VaultTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionIcon: ImageVector? = null,
    actionDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(end = spacing.sm, top = spacing.sm, bottom = spacing.sm),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = colors.textPrimary)
        }
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = VaultTheme.typography.titleLarge, color = colors.textPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = VaultTheme.typography.labelMedium, color = colors.textSecondary)
            }
        }
        if (actionIcon != null && onAction != null) {
            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = actionDescription, tint = colors.textPrimary)
            }
        }
    }
}
