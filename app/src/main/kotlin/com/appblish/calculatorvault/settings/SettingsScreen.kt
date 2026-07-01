package com.appblish.calculatorvault.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.onboarding.SecurityQuestionModal
import com.appblish.calculatorvault.security.DisguiseManager
import com.appblish.calculatorvault.security.PreventUninstallController
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * Settings root (gear from the vault). Three groups from the deck — **Security**,
 * **Protection**, **Personalization** — plus Backup and About. Navigation rows open the
 * dedicated sub-screens; toggle rows persist immediately through [SettingsViewModel]. The
 * two hardening toggles have OS side effects handled here: Prevent-uninstall launches the
 * device-admin consent flow, and Disguise swaps the launcher alias.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onManageFakePasswords: () -> Unit,
    onTheme: () -> Unit,
    onPermissions: () -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val settings = state.settings
    val context = LocalContext.current
    val uninstallController = remember { PreventUninstallController(context) }
    var showRecovery by remember { mutableStateOf(false) }

    // Device-admin consent result → record whether protection actually became active.
    val adminLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.setPreventUninstall(uninstallController.isActive())
        }

    SettingsScaffold(title = "Settings", onBack = onBack, modifier = modifier) {
        SettingsSectionHeader("Security")
        ListRow(
            title = "Change unlock PIN",
            subtitle = "Update the code that opens your vault",
            leadingIcon = Icons.Filled.Lock,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onChangePin,
        )
        ListRow(
            title = "Fake passwords",
            subtitle =
                if (state.decoyCount == 0) {
                    "Add a decoy PIN for duress"
                } else {
                    "${state.decoyCount} decoy space${if (state.decoyCount == 1) "" else "s"}"
                },
            leadingIcon = Icons.Filled.Person,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onManageFakePasswords,
        )
        ListRow(
            title = "Security question",
            subtitle = if (state.hasRecovery) "Set — used to recover a forgotten PIN" else "Not set",
            leadingIcon = Icons.Filled.Star,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = { showRecovery = true },
        )

        SettingsSectionHeader("Protection")
        ListRow(
            title = "Break-in alerts",
            subtitle = "Capture an intruder selfie after wrong PINs",
            leadingIcon = Icons.Filled.Warning,
            leadingChipColor = colors.destructive,
            trailing = RowTrailing.Toggle(settings.breakInAlertsEnabled, viewModel::setBreakInAlerts),
        )
        ListRow(
            title = "Fake password mode",
            subtitle = "Offer decoy vaults under a forced unlock",
            leadingIcon = Icons.Filled.Person,
            trailing = RowTrailing.Toggle(settings.fakePasswordEnabled, viewModel::setFakePassword),
        )
        ListRow(
            title = "Re-lock in background",
            subtitle = "Require the PIN again when the app is reopened",
            leadingIcon = Icons.Filled.Refresh,
            trailing = RowTrailing.Toggle(settings.relockOnBackgroundEnabled, viewModel::setRelockOnBackground),
        )
        ListRow(
            title = "Prevent uninstall",
            subtitle = "Lock the app against removal without your PIN",
            leadingIcon = Icons.Filled.Lock,
            trailing =
                RowTrailing.Toggle(settings.preventUninstallEnabled) { enabled ->
                    if (enabled) {
                        adminLauncher.launch(uninstallController.activationIntent())
                    } else {
                        uninstallController.deactivate()
                        viewModel.setPreventUninstall(false)
                    }
                },
        )
        ListRow(
            title = "Disguise icon & name",
            subtitle =
                if (settings.disguiseIconEnabled) {
                    "Showing plain \"Calc\" tile"
                } else {
                    "Showing default \"Calculator\" tile"
                },
            leadingIcon = Icons.Filled.Face,
            trailing =
                RowTrailing.Toggle(settings.disguiseIconEnabled) { enabled ->
                    DisguiseManager.setAlternate(context, enabled)
                    viewModel.setDisguiseIcon(enabled)
                },
        )
        ListRow(
            title = "Permission management",
            subtitle = "Review the access the vault uses",
            leadingIcon = Icons.Filled.Build,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onPermissions,
        )

        SettingsSectionHeader("Personalization")
        ListRow(
            title = "Theme",
            subtitle = "${settings.keypadSkin.displayName} · ${settings.unlockAnimation.displayName} unlock",
            leadingIcon = Icons.Filled.Settings,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onTheme,
        )

        SettingsSectionHeader("Backup")
        ListRow(
            title = "Backup & restore",
            subtitle = "Encrypted export of your PIN and settings",
            leadingIcon = Icons.Filled.Share,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onBackup,
        )

        SettingsSectionHeader("About")
        ListRow(
            title = "CalcVault",
            subtitle = "Version 0.1.0 · dark theme · single green accent",
            leadingIcon = Icons.Filled.Info,
        )
        Text(
            text =
                "Everything is stored encrypted on this device. Keep a backup somewhere safe — a " +
                    "forgotten PIN with no recovery cannot be reset.",
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(all = VaultTheme.spacing.lg),
        )
    }

    if (showRecovery) {
        SecurityQuestionModal(
            onSave = { setup ->
                viewModel.setRecovery(setup)
                showRecovery = false
            },
            onCancel = { showRecovery = false },
        )
    }
}
