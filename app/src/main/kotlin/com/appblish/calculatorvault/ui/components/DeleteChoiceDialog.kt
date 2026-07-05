package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The delete-choice modal (spec §9; design call D-4 on APP-224). Every vault Delete —
 * category multi-select or viewer — routes through this dialog: the safe default is the
 * full-width green **Move to Recycle Bin** pill; **Delete permanently** is a destructive
 * secondary that needs no second confirm (the explicit choice is the consent); Cancel is a
 * plain text action. The Bin's own "Delete forever?" confirm is a separate, single gate.
 */
@Composable
fun DeleteChoiceDialog(
    itemCount: Int,
    onMoveToRecycleBin: () -> Unit,
    onDeletePermanently: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = VaultTheme.shapes.card,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(spacing.lg),
            ) {
                Text(
                    text = if (itemCount == 1) "Delete this item?" else "Delete $itemCount items?",
                    style = VaultTheme.typography.titleLarge,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Items in the Recycle Bin stay recoverable for 30 days, then delete forever.",
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = spacing.sm, bottom = spacing.lg),
                )
                PillButton(
                    text = "Move to Recycle Bin",
                    onClick = onMoveToRecycleBin,
                    modifier = Modifier.fillMaxWidth(),
                )
                PillButton(
                    text = "Delete permanently",
                    onClick = onDeletePermanently,
                    style = PillButtonStyle.Destructive,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = spacing.xs)) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        }
    }
}
