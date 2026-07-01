package com.appblish.calculatorvault.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
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
            val granted =
                row.permission == null ||
                    ContextCompat.checkSelfPermission(context, row.permission) == PackageManager.PERMISSION_GRANTED
            ListRow(
                title = row.label,
                subtitle = row.description,
                leadingIcon = row.icon,
                leadingChipColor = if (granted) colors.accent else colors.textSecondary,
                trailing = RowTrailing.Badge(if (granted) "Granted" else "Off"),
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
    val permission: String?,
    val icon: ImageVector,
)

@Suppress("DEPRECATION")
@Composable
private fun rememberPermissionRows(): List<PermissionRow> {
    val photoPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    val videoPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            null
        }
    val audioPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            null
        }
    return buildList {
        add(
            PermissionRow(
                "Photos & videos",
                "Import pictures and clips into the vault",
                photoPermission,
                Icons.Filled.Lock,
            ),
        )
        if (videoPermission != null) {
            add(PermissionRow("Videos", "Import and play protected videos", videoPermission, Icons.Filled.Lock))
        }
        if (audioPermission != null) {
            add(PermissionRow("Audio", "Hide music and voice recordings", audioPermission, Icons.Filled.Info))
        }
        add(
            PermissionRow(
                "Camera (break-in selfie)",
                "Captures who tried a wrong PIN, only when enabled",
                Manifest.permission.CAMERA,
                Icons.Filled.Warning,
            ),
        )
        add(
            PermissionRow(
                "Contacts",
                "Hide selected contacts in the vault",
                Manifest.permission.READ_CONTACTS,
                Icons.Filled.Person,
            ),
        )
    }
}
