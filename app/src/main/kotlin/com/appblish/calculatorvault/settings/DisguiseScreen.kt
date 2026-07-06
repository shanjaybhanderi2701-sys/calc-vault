package com.appblish.calculatorvault.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.R
import com.appblish.calculatorvault.security.DisguiseManager
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The icon-disguise control (APP-215 / gap G5c). Reached both from the vault home — the
 * header "Switch app icon" button and the "apps may be at risk" banner — and from Settings.
 * Presents the two launcher tiles backed by the manifest `<activity-alias>` entries and lets
 * the owner pick which one the home screen shows; selecting one flips the enabled alias via
 * [DisguiseManager] and persists the choice. This is the real icon swap — distinct from the
 * decoy-password (fake-password) flow the home button used to open by mistake.
 */
@Composable
fun DisguiseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val context = LocalContext.current

    fun choose(alternate: Boolean) {
        // Enable the chosen alias at the OS level first, then persist so the toggle/state
        // and the actual launcher tile can never drift apart.
        DisguiseManager.setAlternate(context, alternate)
        viewModel.setDisguiseIcon(alternate)
    }

    SettingsScaffold(title = "App disguise", onBack = onBack, modifier = modifier) {
        Text(
            text =
                "Choose how the vault looks on your home screen. Both tiles open the same " +
                    "calculator — only the icon and name a bystander sees change.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
        )

        SettingsSectionHeader("App icon & name")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            DisguiseOption(
                iconRes = R.drawable.ic_launcher_foreground,
                labelRes = R.string.app_name,
                selected = !settings.disguiseIconEnabled,
                onSelect = { choose(false) },
                modifier = Modifier.weight(1f),
            )
            DisguiseOption(
                iconRes = R.drawable.ic_launcher_alt_foreground,
                labelRes = R.string.app_name_alt,
                selected = settings.disguiseIconEnabled,
                onSelect = { choose(true) },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text =
                "After switching, your launcher may briefly drop and re-add the icon while it " +
                    "re-indexes. This is normal — your hidden files are never affected.",
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(all = spacing.lg),
        )
    }
}

@Composable
private fun DisguiseOption(
    iconRes: Int,
    labelRes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val borderColor = if (selected) colors.accent else colors.divider

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .clip(RoundedCornerShape(14.dp))
                .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
                .clickable(onClick = onSelect)
                .background(colors.surface)
                .padding(spacing.lg),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceVariant),
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = stringResource(labelRes),
            style = VaultTheme.typography.bodyLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Spacer(Modifier.size(spacing.xs))
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) colors.accent else colors.surfaceVariant),
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color(0xFF06210F))
            }
        }
    }
}
