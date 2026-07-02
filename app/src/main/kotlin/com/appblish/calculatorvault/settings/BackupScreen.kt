package com.appblish.calculatorvault.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * Settings → Backup & restore. Create seals an encrypted [BackupManager] blob under a
 * password the owner sets and offers to share it off-device; Restore takes a pasted blob +
 * password and rewrites the PIN/settings. Losing the backup password makes the backup
 * unrecoverable — stated plainly, since the whole point is that no one else can read it.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val context = LocalContext.current

    SettingsScaffold(title = "Backup & restore", onBack = onBack, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg)) {
            SettingsSectionHeader("Create a backup")
            Text(
                text =
                    "Seals your PIN, decoy PINs, recovery and settings into one encrypted string. " +
                        "Keep the password — it can't be recovered.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = spacing.sm),
            )
            OutlinedTextField(
                value = state.backupPassword,
                onValueChange = viewModel::onBackupPasswordChange,
                label = { Text("Backup password (min 6)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            PillButton(
                text = "Create backup",
                onClick = viewModel::createBackup,
                enabled = state.backupPassword.length >= 6 && !state.working,
                modifier = Modifier.padding(top = spacing.md),
            )

            val blob = state.createdBlob
            if (blob != null) {
                OutlinedTextField(
                    value = blob,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Your encrypted backup") },
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                )
                PillButton(
                    text = "Share backup",
                    onClick = {
                        val intent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, blob)
                            }
                        context.startActivity(Intent.createChooser(intent, "Share backup"))
                    },
                    style = PillButtonStyle.Secondary,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }

            SettingsSectionHeader("Restore a backup")
            OutlinedTextField(
                value = state.restoreBlob,
                onValueChange = viewModel::onRestoreBlobChange,
                label = { Text("Paste backup string") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.restorePassword,
                onValueChange = viewModel::onRestorePasswordChange,
                label = { Text("Backup password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
            )
            PillButton(
                text = "Restore backup",
                onClick = viewModel::restore,
                enabled = state.restoreBlob.isNotBlank() && state.restorePassword.isNotEmpty() && !state.working,
                style = PillButtonStyle.Destructive,
                modifier = Modifier.padding(top = spacing.md),
            )

            state.message?.let {
                Text(
                    text = it,
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.accent,
                    modifier = Modifier.padding(top = spacing.md)
                )
            }
            state.error?.let {
                Text(
                    text = it,
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.destructive,
                    modifier = Modifier.padding(top = spacing.md)
                )
            }
        }
    }
}
