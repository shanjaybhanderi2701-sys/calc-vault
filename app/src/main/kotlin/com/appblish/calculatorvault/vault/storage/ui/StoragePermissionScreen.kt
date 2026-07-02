package com.appblish.calculatorvault.vault.storage.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * The All Files Access primer, in our design with **calm native-trust copy** (flow-logic
 * §4, taste-guide APP-136) — never scare framing. It is the point-of-need gate for the
 * hidden public `.CalcVault/` storage: the vault keeps everything it hides in an encrypted
 * folder on shared storage so your hidden files **stay safe even if the app is uninstalled
 * and reinstalled**, and Android requires this one permission to use that folder.
 *
 * On API 30+ the button opens the system All Files Access screen and the resume re-check
 * flips [onGranted] the moment it comes back granted; on API 29- it uses the ordinary
 * storage runtime dialog. A denial simply stays here — never a dead end.
 */
@Composable
fun StoragePermissionScreen(
    onGranted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spacing = VaultTheme.spacing
    val colors = VaultTheme.colors

    var granted by remember { mutableStateOf(StoragePermissions.hasAllFilesAccess(context)) }

    LifecycleResumeEffect(Unit) {
        granted = StoragePermissions.hasAllFilesAccess(context)
        if (granted) onGranted()
        onPauseOrDispose { }
    }

    val runtimeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
            if (ok) onGranted()
        }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        VaultTopBar(title = "Keep your hidden files safe", onBack = onBack)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xl),
        ) {
            RoundIconBadge(icon = Icons.Filled.Lock)
            Spacer(Modifier.height(spacing.lg))
            Text(
                text = "Storage access",
                style = VaultTheme.typography.headlineSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text =
                    "CalcVault keeps everything you hide in an encrypted, private folder on this " +
                        "device. Android needs your permission to use that folder — this is what lets " +
                        "your hidden files stay safe even if the app is reinstalled. Nothing is uploaded.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }

        Spacer(Modifier.height(spacing.xl))

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg)
                    .clip(VaultTheme.shapes.card)
                    .background(colors.surface)
                    .padding(spacing.lg),
        ) {
            Text(
                text = "Why this permission",
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text =
                    "Your files are encrypted and stored under a hidden name, so no one can read them " +
                        "from a file manager. Keeping them in this folder — instead of inside the app — " +
                        "is what makes them survive an uninstall.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }

        Spacer(Modifier.height(spacing.lg))

        PillButton(
            text = if (granted) "Access granted" else "Allow storage access",
            onClick = {
                if (granted) {
                    onGranted()
                } else if (StoragePermissions.usesRuntimeWritePermission()) {
                    runtimeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    StoragePermissions.allFilesAccessIntent(context)?.let { intent ->
                        runCatching { context.startActivity(intent) }
                    }
                }
            },
            style = if (granted) PillButtonStyle.Primary else PillButtonStyle.Secondary,
            modifier = Modifier.padding(horizontal = spacing.lg),
        )
        Spacer(Modifier.height(spacing.xl))
    }
}

/** Accent-tinted round badge holding a single glyph — the primer's hero mark. */
@Composable
private fun RoundIconBadge(icon: ImageVector) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(32.dp))
    }
}
