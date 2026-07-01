package com.appblish.calculatorvault.applock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.applock.AppLockFilter
import com.appblish.calculatorvault.applock.InstalledApp
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** The **All / Unlocked / Locked** segmented control from the deck. */
@Composable
fun SegmentedFilter(
    selected: AppLockFilter,
    onSelect: (AppLockFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Row(
        modifier =
            modifier
                .clip(VaultTheme.shapes.pill)
                .background(colors.surface)
                .padding(VaultTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(VaultTheme.spacing.xs),
    ) {
        AppLockFilter.entries.forEach { filter ->
            val active = filter == selected
            Text(
                text = filter.displayName,
                style = VaultTheme.typography.labelLarge,
                color = if (active) colors.onAccent else colors.textSecondary,
                modifier =
                    Modifier
                        .clip(VaultTheme.shapes.pill)
                        .background(if (active) colors.accent else colors.surface)
                        .clickable { onSelect(filter) }
                        .padding(horizontal = VaultTheme.spacing.lg, vertical = VaultTheme.spacing.sm),
            )
        }
    }
}

/** Rounded search field with a leading magnifier (deck + xlock picker). */
@Composable
fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search apps",
) {
    val colors = VaultTheme.colors
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary) },
        placeholder = { Text(placeholder, color = colors.textDisabled) },
        shape = VaultTheme.shapes.pill,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** An app row with the real launcher icon and a trailing lock [Switch] (AppLock list). */
@Composable
fun AppLockRow(
    app: InstalledApp,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(
            horizontal = VaultTheme.spacing.lg,
            vertical = VaultTheme.spacing.md
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, label = app.label)
        Spacer(Modifier.width(VaultTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = VaultTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (app.suggested) {
                Text(
                    text = "Suggested",
                    style = VaultTheme.typography.labelMedium,
                    color = colors.accent,
                )
            }
        }
        Spacer(Modifier.width(VaultTheme.spacing.sm))
        Switch(
            checked = app.locked,
            onCheckedChange = onToggle,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedTrackColor = colors.surfaceVariant,
                    uncheckedBorderColor = colors.divider,
                ),
        )
    }
}

/** An app row with a trailing [Checkbox] used by the picker's multi-select. */
@Composable
fun AppPickRow(
    app: InstalledApp,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = VaultTheme.spacing.lg, vertical = VaultTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, label = app.label)
        Spacer(Modifier.width(VaultTheme.spacing.md))
        Text(
            text = app.label,
            style = VaultTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = colors.accent,
                    uncheckedColor = colors.divider,
                    checkmarkColor = colors.onAccent,
                ),
        )
    }
}

/** Small section header ("Suggested", "System", "All apps"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = VaultTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(
            horizontal = VaultTheme.spacing.lg,
            vertical = VaultTheme.spacing.sm
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/** A muted round icon badge used in empty states. */
@Composable
fun RoundIconBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(88.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.accent.copy(alpha = 0.14f)),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(40.dp))
    }
}
