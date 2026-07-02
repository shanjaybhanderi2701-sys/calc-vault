package com.appblish.calculatorvault.applock.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.appblish.calculatorvault.applock.AppLockSettings
import com.appblish.calculatorvault.applock.LockMethod
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * Protection settings for AppLock (deck section G — "Applock Setting"): the unlock method,
 * how soon a locked app re-locks after you leave it, and the opt-in Intruder Selfie with its
 * wrong-attempt threshold. Enabling Intruder Selfie requests the camera permission at that
 * moment — never before (flow-logic §4).
 */
@Composable
fun AppLockSettingsScreen(
    settings: AppLockSettings,
    onChange: (AppLockSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spacing = VaultTheme.spacing

    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onChange(settings.copy(intruderEnabled = granted))
        }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        VaultTopBar(title = "Protection settings", onBack = onBack)

        Group(title = "Unlock method") {
            LockMethod.entries.forEach { method ->
                ChoiceRow(
                    label = method.displayName,
                    selected = settings.lockMethod == method,
                    onClick = { onChange(settings.copy(lockMethod = method)) },
                )
            }
        }

        Group(title = "Re-lock") {
            AppLockSettings.RELOCK_OPTIONS.forEach { (delay, label) ->
                ChoiceRow(
                    label = label,
                    selected = settings.relockDelayMs == delay,
                    onClick = { onChange(settings.copy(relockDelayMs = delay)) },
                )
            }
        }

        Group(title = "Intruder Selfie") {
            SwitchRow(
                label = "Capture on wrong attempts",
                subtitle = "Take a front-camera photo when someone fails to unlock.",
                checked = settings.intruderEnabled,
                onCheckedChange = { enable ->
                    if (!enable) {
                        onChange(settings.copy(intruderEnabled = false))
                    } else {
                        val granted =
                            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            onChange(
                                settings.copy(intruderEnabled = true)
                            )
                        } else {
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                },
            )
            if (settings.intruderEnabled) {
                StepperRow(
                    label = "Capture after",
                    value = settings.intruderThreshold,
                    unit = if (settings.intruderThreshold == 1) "wrong try" else "wrong tries",
                    onDecrement = {
                        onChange(
                            settings.copy(
                                intruderThreshold =
                                    (settings.intruderThreshold - 1).coerceAtLeast(AppLockSettings.MIN_THRESHOLD),
                            ),
                        )
                    },
                    onIncrement = {
                        onChange(
                            settings.copy(
                                intruderThreshold =
                                    (settings.intruderThreshold + 1).coerceAtMost(AppLockSettings.MAX_THRESHOLD),
                            ),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.size(spacing.xxl))
    }
}

@Composable
private fun Group(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm)) {
        Text(
            text = title.uppercase(),
            style = VaultTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(vertical = spacing.sm),
        )
        Column(
            modifier = Modifier.fillMaxWidth().clip(VaultTheme.shapes.card).background(colors.surface),
        ) { content() }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = VaultTheme.spacing.lg, vertical = VaultTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = VaultTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = colors.accent)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = VaultTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = VaultTheme.spacing.lg,
            vertical = VaultTheme.spacing.md
        ),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = VaultTheme.typography.bodyLarge, color = colors.textPrimary)
            Text(text = subtitle, style = VaultTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    unit: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    val colors = VaultTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = VaultTheme.spacing.lg,
            vertical = VaultTheme.spacing.md
        ),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = VaultTheme.typography.bodyLarge, color = colors.textPrimary)
            Text(text = "$value $unit", style = VaultTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepButton(label = "−", onClick = onDecrement)
            Text(text = value.toString(), style = VaultTheme.typography.titleMedium, color = colors.textPrimary)
            StepButton(icon = true, onClick = onIncrement)
        }
    }
}

@Composable
private fun StepButton(
    label: String = "",
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant)
                .clickable(onClick = onClick),
    ) {
        if (icon) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Increase",
                tint = colors.textPrimary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(text = label, style = VaultTheme.typography.titleMedium, color = colors.textPrimary)
        }
    }
}
