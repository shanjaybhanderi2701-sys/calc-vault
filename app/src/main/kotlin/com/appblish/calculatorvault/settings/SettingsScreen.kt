package com.appblish.calculatorvault.settings

import android.app.ActivityManager
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.security.DisguiseManager
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.RowTrailing

/**
 * Minimal Phase-1 Settings root (build spec §0 caps Settings at "the minimum needed for
 * the flows"; row set per design sign-off S22 on APP-224): App language, Change password,
 * Switch app icon, Theme, All files access, the opt-in Hide-from-recents toggle (spec
 * §10), and version/about. Everything else — backup, fake passwords, break-in alerts,
 * prevent-uninstall, recovery — is hidden this phase, not teased.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onTheme: () -> Unit,
    onPermissions: () -> Unit,
    onLanguage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current

    SettingsScaffold(title = "Settings", onBack = onBack, modifier = modifier) {
        SettingsSectionHeader("General")
        ListRow(
            title = "App language",
            subtitle = settings.appLanguage,
            leadingIcon = Icons.Filled.Person,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onLanguage,
        )
        ListRow(
            title = "Change password",
            subtitle = "Update the password that opens your vault",
            leadingIcon = Icons.Filled.Lock,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onChangePin,
        )
        ListRow(
            title = "Theme",
            subtitle = "${settings.keypadSkin.displayName} · ${settings.unlockAnimation.displayName} unlock",
            leadingIcon = Icons.Filled.Settings,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onTheme,
        )

        SettingsSectionHeader("Privacy")
        ListRow(
            title = "Switch app icon",
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
            title = "Hide from recents",
            subtitle = "Remove the calculator from the app switcher (previews are always blanked)",
            leadingIcon = Icons.Filled.Refresh,
            trailing =
                RowTrailing.Toggle(settings.hideFromRecentsEnabled) { enabled ->
                    // Apply to the live task immediately; MainActivity re-applies the
                    // persisted value on every launch. Off by default per spec §10.
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    activityManager?.appTasks?.firstOrNull()?.setExcludeFromRecents(enabled)
                    viewModel.setHideFromRecents(enabled)
                },
        )
        ListRow(
            title = "All files access",
            subtitle = "Review the access the vault uses",
            leadingIcon = Icons.Filled.Build,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onPermissions,
        )

        SettingsSectionHeader("About")
        ListRow(
            title = "CalcVault",
            subtitle = "Version 1.0.0",
            leadingIcon = Icons.Filled.Info,
        )
    }
}
