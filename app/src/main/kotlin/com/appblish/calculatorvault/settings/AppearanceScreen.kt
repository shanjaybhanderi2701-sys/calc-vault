package com.appblish.calculatorvault.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.theme.AccentColor
import com.appblish.calculatorvault.ui.theme.ThemeMode
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * Settings → Appearance (APP-525, spec §1). Two token controls that recolor the whole app
 * live, because both write through the single [com.appblish.calculatorvault.ui.theme.ThemeController]:
 *
 *  - **Theme mode** — Light / Dark / System default (default Dark).
 *  - **Accent color** — a tappable swatch grid over the curated [AccentColor] palette
 *    (default Blue). Tapping a swatch swaps the accent token everywhere instantly.
 *
 * Both selections persist through the encrypted settings store, so they survive a restart.
 */
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    SettingsScaffold(title = "Appearance", onBack = onBack, modifier = modifier) {
        SettingsSectionHeader("Theme")
        ThemeMode.entries.forEach { mode ->
            ThemeModeRow(
                mode = mode,
                selected = settings.themeMode == mode,
                onClick = { viewModel.setThemeMode(mode) },
            )
        }

        SettingsSectionHeader("Accent color")
        AccentGrid(
            selected = settings.accentColor,
            onSelect = { viewModel.setAccentColor(it) },
        )
    }
}

/** One selectable theme-mode row with a trailing check on the active choice. */
@Composable
private fun ThemeModeRow(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
    ) {
        Text(
            text = mode.displayName,
            style = VaultTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = colors.accent,
            )
        }
    }
}

/**
 * A wrapping grid of accent swatches (5 per row). Rendered as fixed rows rather than a
 * LazyVerticalGrid so it composes inside the scaffold's scrolling column without a nested-scroll
 * conflict, and stays trivially testable.
 */
@Composable
private fun AccentGrid(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit,
    perRow: Int = 5,
) {
    val spacing = VaultTheme.spacing
    Column(
        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        AccentColor.entries.chunked(perRow).forEach { rowAccents ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                rowAccents.forEach { accent ->
                    AccentSwatch(
                        accent = accent,
                        selected = accent == selected,
                        onClick = { onSelect(accent) },
                    )
                }
                // Pad the final short row so swatches stay left-aligned on the grid.
                repeat(perRow - rowAccents.size) { Spacer(modifier = Modifier.size(48.dp)) }
            }
        }
    }
}

/** A single accent circle; the active one gets a ring + a check in its on-accent ink. */
@Composable
private fun AccentSwatch(
    accent: AccentColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(accent.swatch)
                .then(
                    if (selected) {
                        Modifier.border(width = 2.dp, color = colors.textPrimary, shape = CircleShape)
                    } else {
                        Modifier
                    },
                ).clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "${accent.displayName} selected",
                tint = accent.onInk,
            )
        }
    }
}
