package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The centered modal card from the deck (create-folder, security-question,
 * destructive-confirm). Renders a titled surface over a scrim with a caller-supplied
 * body and a two-button footer. Pass a [PillButtonStyle.Destructive] confirm for
 * irreversible actions.
 */
@Composable
fun VaultModal(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    confirmStyle: PillButtonStyle = PillButtonStyle.Primary,
    dismissLabel: String = "Cancel",
    confirmEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = VaultTheme.shapes.card,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(spacing.xl)) {
                Text(
                    text = title,
                    style = VaultTheme.typography.titleLarge,
                    color = colors.textPrimary,
                )
                if (message != null) {
                    Text(
                        text = message,
                        style = VaultTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
                if (content != null) {
                    Column(modifier = Modifier.padding(top = spacing.lg)) {
                        content()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.xl),
                ) {
                    PillButton(
                        text = dismissLabel,
                        onClick = onDismiss,
                        style = PillButtonStyle.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    PillButton(
                        text = confirmLabel,
                        onClick = onConfirm,
                        style = confirmStyle,
                        enabled = confirmEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
