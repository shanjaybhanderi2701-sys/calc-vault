package com.appblish.calculatorvault.explore.junk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * Junk Cleaner. Idle offers a single scan CTA; scanning shows a spinner; results list the
 * reclaimable buckets with per-bucket sizes and a tap-to-toggle green check, footed by the
 * green **Clean** CTA; the done state reports the space freed. Nothing here is destructive
 * to vault content, so the palette stays green (no red).
 */
@Composable
fun JunkCleanerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JunkCleanerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = "Junk Cleaner", onBack = onBack)

        when (state.phase) {
            JunkPhase.IDLE ->
                CenteredMessage(
                    headline = "Free up space",
                    body = "Scan for cache and residual files the vault can clear. Hidden content stays untouched.",
                    ctaLabel = "Scan now",
                    onCta = viewModel::scan,
                )

            JunkPhase.SCANNING -> Busy("Scanning for junk…")

            JunkPhase.CLEANING -> Busy("Cleaning…")

            JunkPhase.RESULTS ->
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = formatBytes(state.selectedBytes) + " selected of " + formatBytes(state.totalBytes),
                        style = VaultTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                    )
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(state.categories, key = { it.key }) { category ->
                            JunkRow(category = category, onToggle = { viewModel.toggle(category.key) })
                        }
                    }
                    PillButton(
                        text = if (state.selectedBytes > 0) "Clean ${formatBytes(state.selectedBytes)}" else "Clean",
                        onClick = viewModel::clean,
                        enabled = state.canClean,
                        leadingIcon = Icons.Filled.Delete,
                        modifier = Modifier.padding(spacing.lg),
                    )
                }

            JunkPhase.DONE ->
                CenteredMessage(
                    headline = "All clean",
                    body = "Freed ${formatBytes(state.freedBytes)}. The vault is tidy.",
                    ctaLabel = "Scan again",
                    onCta = viewModel::scan,
                )
        }
    }
}

@Composable
private fun JunkRow(
    category: JunkCategory,
    onToggle: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (category.selected) colors.accent else colors.surfaceVariant),
        ) {
            if (category.selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = colors.onAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.width(spacing.md))
        Text(
            text = category.label,
            style = VaultTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatBytes(category.bytes),
            style = VaultTheme.typography.labelLarge,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun Busy(
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = colors.accent)
        Text(text = label, style = VaultTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}

@Composable
private fun CenteredMessage(
    headline: String,
    body: String,
    ctaLabel: String,
    onCta: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(
                    96.dp
                ).clip(RoundedCornerShape(28.dp))
                .background(colors.accent.copy(alpha = 0.16f)),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = colors.accent, modifier = Modifier.size(44.dp))
        }
        Text(
            text = headline,
            style = VaultTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = spacing.xl),
        )
        Text(
            text = body,
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
        PillButton(text = ctaLabel, onClick = onCta, modifier = Modifier.padding(top = spacing.xxl))
    }
}

/** Human-readable byte size, e.g. 34.0 MB. Kept local to the Junk Cleaner. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
}
