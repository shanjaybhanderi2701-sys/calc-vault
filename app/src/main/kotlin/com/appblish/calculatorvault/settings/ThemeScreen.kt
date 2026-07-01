package com.appblish.calculatorvault.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * Settings → Theme (deck: "Theme Flow — Calculator Lock"). Lets the owner pick a keypad
 * skin — accent + key silhouette, layout untouched so the cover stays a plain calculator —
 * and the unlock animation. A live preview shows the selected skin so the choice is
 * genuinely functional, not just stored. Each pick persists immediately.
 */
@Composable
fun ThemeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    SettingsScaffold(title = "Theme", onBack = onBack, modifier = modifier) {
        KeypadPreview(skin = settings.keypadSkin)

        SettingsSectionHeader("Keypad skin")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            KeypadSkin.entries.take(2).forEach { skin ->
                SkinSwatch(
                    skin = skin,
                    selected = skin == settings.keypadSkin,
                    onSelect = { viewModel.setKeypadSkin(skin) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.size(spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            KeypadSkin.entries.drop(2).forEach { skin ->
                SkinSwatch(
                    skin = skin,
                    selected = skin == settings.keypadSkin,
                    onSelect = { viewModel.setKeypadSkin(skin) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SettingsSectionHeader("Unlock animation")
        UnlockAnimation.entries.forEach { animation ->
            AnimationOption(
                animation = animation,
                selected = animation == settings.unlockAnimation,
                onSelect = { viewModel.setUnlockAnimation(animation) },
            )
        }

        Text(
            text =
                "Skins change only the accent colour and key shape — never the calculator layout — " +
                    "so the disguise is never given away.",
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(all = spacing.lg),
        )
    }
}

@Composable
private fun KeypadPreview(skin: KeypadSkin) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val keyShape = if (skin.keyShape == KeySkinShape.CIRCLE) CircleShape else RoundedCornerShape(14.dp)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .padding(spacing.lg),
    ) {
        Text(
            text = "1,234",
            style = VaultTheme.typography.headlineMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing.md),
        )
        // Two sample rows: neutral digit keys + one accent key, showing the skin at a glance.
        listOf(listOf("7", "8", "9"), listOf("4", "5", "=")).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                row.forEach { label ->
                    val isAccent = label == "="
                    PreviewKey(
                        label = label,
                        shape = keyShape,
                        container = if (isAccent) skin.accent else colors.surfaceVariant,
                        content = if (isAccent) Color(0xFF06210F) else colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewKey(
    label: String,
    shape: androidx.compose.ui.graphics.Shape,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.aspectRatio(1.6f).clip(shape).background(container),
    ) {
        Text(text = label, style = VaultTheme.typography.titleLarge, color = content)
    }
}

@Composable
private fun SkinSwatch(
    skin: KeypadSkin,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val borderColor = if (selected) skin.accent else colors.divider

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .clip(RoundedCornerShape(14.dp))
                .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
                .clickable(onClick = onSelect)
                .background(colors.surface)
                .padding(spacing.md),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(if (skin.keyShape == KeySkinShape.CIRCLE) CircleShape else RoundedCornerShape(12.dp))
                    .background(skin.accent),
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color(0xFF06210F))
            }
        }
        Text(
            text = skin.displayName,
            style = VaultTheme.typography.labelMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}

@Composable
private fun AnimationOption(
    animation: UnlockAnimation,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = animation.displayName, style = VaultTheme.typography.bodyLarge, color = colors.textPrimary)
            Text(text = animation.description, style = VaultTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Spacer(Modifier.width(spacing.sm))
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) colors.accent else colors.divider, CircleShape),
        ) {
            if (selected) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(colors.accent))
            }
        }
    }
}
