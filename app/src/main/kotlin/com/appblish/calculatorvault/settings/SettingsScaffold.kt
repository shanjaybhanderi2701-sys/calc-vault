package com.appblish.calculatorvault.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * Shared chrome for the Phase 5 settings sub-screens: the near-black canvas, a back button +
 * large title header matching the deck, and a vertically scrolling content column. Keeps the
 * Settings/Theme/Permission/Backup screens visually identical.
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Text(text = title, style = VaultTheme.typography.headlineSmall, color = colors.textPrimary)
        }

        val bodyModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(modifier = Modifier.fillMaxSize().then(bodyModifier)) {
            content()
        }
    }
}

/** A muted, uppercase-feel section label between groups of rows. */
@Composable
fun SettingsSectionHeader(text: String) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Text(
        text = text,
        style = VaultTheme.typography.labelLarge,
        color = colors.accent,
        modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.lg, bottom = spacing.xs),
    )
}
