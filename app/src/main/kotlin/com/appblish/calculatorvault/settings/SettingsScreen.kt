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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
 * Settings root: App language, Change password, Switch app icon, All files access,
 * the opt-in Hide-from-recents toggle (spec §10), and version/about (row set per design
 * sign-off S22 on APP-224). PIN Recovery W4 adds the **PIN Recovery** entry (management hub,
 * screen 14) and the **Allow screenshots** toggle (release-build FLAG_SECURE, default OFF —
 * screen 15) to the Privacy section. Everything else — backup, fake passwords, break-in
 * alerts, prevent-uninstall — stays hidden this phase, not teased.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onPermissions: () -> Unit,
    onLanguage: () -> Unit,
    onPinRecovery: () -> Unit,
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
            title = "PIN Recovery",
            subtitle =
                if (state.hasRecovery) {
                    "Recovery is set up · view question, regenerate code"
                } else {
                    "Set up a way back in if you forget your PIN"
                },
            leadingIcon = Icons.Filled.Star,
            trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
            onClick = onPinRecovery,
        )
        ListRow(
            title = "Allow screenshots",
            subtitle = "Off blocks screenshots inside the vault and hides it from the app switcher.",
            leadingIcon = Icons.Filled.Warning,
            trailing =
                RowTrailing.Toggle(settings.allowScreenshotsEnabled) { enabled ->
                    // Flip FLAG_SECURE on the live window immediately for instant feedback;
                    // MainActivity re-applies from the persisted value on its next resume.
                    // Off (default) → secure; on → screenshots allowed.
                    context.applyWindowSecure(secure = !enabled)
                    viewModel.setAllowScreenshots(enabled)
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
