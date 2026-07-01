package com.appblish.calculatorvault.applock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.applock.LockMethod
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The full-screen AppLock challenge, faithful to the board's xlock role-model lock screen
 * (role-model study, APP-142): the target app's icon at the top, "Enter your PIN", four
 * masked dots, a circular numeric keypad, and a "Change password type" affordance. Rendered
 * on the vault's near-black canvas with the single green accent — never xlock's blue. Stateless
 * so it is driven by [LockScreenActivity] and previewable.
 */
@Composable
fun LockScreenContent(
    appLabel: String,
    appIcon: ImageBitmap?,
    method: LockMethod,
    pinLength: Int,
    maxPinLength: Int,
    error: Boolean,
    biometricAvailable: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
    onChangeMethod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.canvas)
                .padding(horizontal = spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(spacing.xxl))
        AppBadge(appLabel = appLabel, appIcon = appIcon)

        Spacer(Modifier.height(spacing.lg))
        Text(
            text = "Enter your ${method.displayName}",
            style = VaultTheme.typography.titleLarge,
            color = colors.textPrimary,
        )
        Text(
            text = "Unlock $appLabel",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.xs),
        )

        Spacer(Modifier.height(spacing.xl))
        PinDots(filled = pinLength, total = maxPinLength, error = error)

        Text(
            text = if (error) "Wrong ${method.displayName}. Try again." else " ",
            style = VaultTheme.typography.labelMedium,
            color = if (error) colors.destructive else colors.textSecondary,
            modifier = Modifier.padding(top = spacing.md),
        )

        Spacer(Modifier.weight(1f))

        PinPad(
            onDigit = onDigit,
            onBackspace = onBackspace,
            biometricAvailable = biometricAvailable && method == LockMethod.Biometric,
            onBiometric = onBiometric,
        )

        Text(
            text = "Change password type",
            style = VaultTheme.typography.labelLarge,
            color = colors.accent,
            modifier =
                Modifier
                    .padding(vertical = spacing.lg)
                    .clip(VaultTheme.shapes.chip)
                    .clickable(onClick = onChangeMethod)
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
        )
    }
}

@Composable
private fun AppBadge(
    appLabel: String,
    appIcon: ImageBitmap?,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceVariant),
    ) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = appLabel, modifier = Modifier.size(48.dp))
        } else {
            Text(
                text = appLabel.firstOrNull()?.uppercase() ?: "?",
                style = VaultTheme.typography.headlineSmall,
                color = colors.accent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PinDots(
    filled: Int,
    total: Int,
    error: Boolean,
) {
    val colors = VaultTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md)) {
        repeat(total) { index ->
            val on = index < filled
            val tint =
                when {
                    error -> colors.destructive
                    on -> colors.accent
                    else -> colors.divider
                }
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(tint),
            )
        }
    }
}

@Composable
private fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    biometricAvailable: Boolean,
    onBiometric: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(VaultTheme.spacing.xl)) {
                row.forEach { ch -> KeypadKey(label = ch.toString()) { onDigit(ch) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(VaultTheme.spacing.xl)) {
            if (biometricAvailable) {
                KeypadIconKey(icon = Icons.Filled.Face, description = "Use biometric", onClick = onBiometric)
            } else {
                KeypadSpacer()
            }
            KeypadKey(label = "0") { onDigit('0') }
            KeypadIconKey(icon = Icons.Filled.Clear, description = "Backspace", onClick = onBackspace)
        }
    }
}

@Composable
private fun KeypadKey(
    label: String,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .clickable(onClick = onClick),
    ) {
        Text(text = label, style = VaultTheme.typography.headlineSmall, color = colors.textPrimary)
    }
}

@Composable
private fun KeypadIconKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = colors.textSecondary)
    }
}

@Composable
private fun KeypadSpacer() {
    Box(modifier = Modifier.size(72.dp)) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Transparent,
        )
    }
}
