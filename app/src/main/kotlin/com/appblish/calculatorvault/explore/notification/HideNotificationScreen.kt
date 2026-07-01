package com.appblish.calculatorvault.explore.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * Hide Notification. A master switch suppresses all app notifications while the vault is
 * active; below it, a per-app list lets the user pick exactly which apps to silence. A
 * quiet info banner names the Notification-Access permission the enforcement service needs
 * (the Android idiom for the deck's "grant access" step) — wiring the live
 * NotificationListenerService is a Phase 5 permission task.
 */
@Composable
fun HideNotificationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HideNotificationViewModel = viewModel(),
) {
    val hideAll by viewModel.hideAll.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = "Hide Notification", onBack = onBack)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        text = "Needs Notification Access. Granted in system settings when you first enable hiding.",
                        style = VaultTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }
            item {
                ListRow(
                    title = "Hide all notifications",
                    subtitle = "Silence every app while the vault is open",
                    leadingIcon = Icons.Filled.Notifications,
                    leadingChipColor = colors.accent,
                    trailing = RowTrailing.Toggle(checked = hideAll, onCheckedChange = viewModel::setHideAll),
                )
            }
            item {
                Text(
                    text = "Or choose apps",
                    style = VaultTheme.typography.labelLarge,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = spacing.lg, top = spacing.md, bottom = spacing.xs),
                )
            }
            items(rules, key = { it.packageName }) { rule ->
                ListRow(
                    title = rule.label,
                    leadingIcon = Icons.Filled.Notifications,
                    leadingChipColor = colors.accent,
                    trailing =
                        RowTrailing.Toggle(
                            checked = hideAll || rule.hidden,
                            onCheckedChange = { viewModel.setHidden(rule.packageName, it) },
                        ),
                )
            }
        }
    }
}
