package com.appblish.calculatorvault.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.storage.StoragePermissions

/**
 * Settings → Permission management (deck epic G, native-trust framing). Shows each access
 * the vault uses, whether it is currently granted, and *why* — reassuring rather than
 * alarming, per the taste-guide note in the design spec. Android only lets an app revoke a
 * permission by sending the user to the system app-info screen, so that is the single action.
 */
@Composable
fun PermissionManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    val rows = rememberPermissionRows()

    SettingsScaffold(title = "Permissions", onBack = onBack, modifier = modifier) {
        Text(
            text =
                "These let the vault import and protect your content. Everything stays encrypted " +
                    "on this device — nothing is uploaded.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
        )

        rows.forEach { row ->
            ListRow(
                title = row.label,
                subtitle = row.description,
                leadingIcon = row.icon,
                leadingChipColor = if (row.granted) colors.accent else colors.textSecondary,
                trailing = RowTrailing.Badge(if (row.granted) "Granted" else "Off"),
            )
        }

        PillButton(
            text = "Open system permission settings",
            onClick = {
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(intent)
            },
            modifier = Modifier.padding(all = spacing.lg),
        )
    }
}

private data class PermissionRow(
    val label: String,
    val description: String,
    val granted: Boolean,
    val icon: ImageVector,
)

@Composable
private fun rememberPermissionRows(): List<PermissionRow> {
    val context = LocalContext.current
    return buildList {
        // All Files Access is the SOLE storage permission the vault uses (board directive
        // APP-219 / APP-203): on API 30+ it already grants read/write to every media category,
        // so no granular READ_MEDIA_* rows are surfaced — Settings shows the one permission the
        // vault actually depends on, matching xlock.
        add(
            PermissionRow(
                "All files access",
                "Import photos, videos, audio and files into the vault and restore them",
                StoragePermissions.hasAllFilesAccess(context),
                Icons.Filled.Lock,
            ),
        )
        add(
            PermissionRow(
                "Camera (break-in selfie)",
                "Captures who tried a wrong PIN, only when enabled",
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
                Icons.Filled.Warning,
            ),
        )
    }
}
