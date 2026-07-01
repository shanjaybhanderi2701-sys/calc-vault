package com.appblish.calculatorvault.applock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.appblish.calculatorvault.applock.AppLockPermissions
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * The permission primer, rendered in our design with **calm native-trust copy** — never
 * xlock's "high risk!/>90%" scare framing (flow-logic §4, taste-guide APP-136). The xlock
 * *sequence* is preserved: **Usage Access → Accessibility**, one plain-language step at a
 * time, each explaining plainly why it's needed. A granted step shows a green check; a
 * denial simply returns here (the resume re-check), never a dead end.
 */
@Composable
fun AppLockPermissionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spacing = VaultTheme.spacing
    val colors = VaultTheme.colors

    var hasUsage by remember { mutableStateOf(false) }
    var hasAccessibility by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        hasUsage = AppLockPermissions.hasUsageAccess(context)
        hasAccessibility = AppLockPermissions.hasAccessibility(context)
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        VaultTopBar(title = "Turn on protection", onBack = onBack)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xl),
        ) {
            RoundIconBadge(icon = Icons.Filled.Lock)
            Spacer(Modifier.height(spacing.lg))
            Text(
                text = "Keep locked apps protected",
                style = VaultTheme.typography.headlineSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text =
                    "CalcVault needs two Android permissions to show the lock screen the moment a " +
                        "locked app opens. These stay on your device — nothing is uploaded.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }

        Spacer(Modifier.height(spacing.xl))

        PermissionStep(
            index = 1,
            title = "Usage Access",
            rationale = "Lets CalcVault notice when a protected app comes to the front.",
            granted = hasUsage,
            onGrant = { runCatching { context.startActivity(AppLockPermissions.usageAccessIntent()) } },
        )
        PermissionStep(
            index = 2,
            title = "Accessibility",
            rationale = "Lets CalcVault raise the lock screen instantly, every time.",
            granted = hasAccessibility,
            onGrant = { runCatching { context.startActivity(AppLockPermissions.accessibilityIntent()) } },
        )

        Spacer(Modifier.height(spacing.lg))

        val allSet = hasUsage && hasAccessibility
        PillButton(
            text = if (allSet) "Protection is on" else "Why do I see this?",
            onClick = onBack,
            style = if (allSet) PillButtonStyle.Primary else PillButtonStyle.Secondary,
            enabled = true,
            modifier = Modifier.padding(spacing.lg),
        )
        Spacer(Modifier.height(spacing.xl))
    }
}

@Composable
private fun PermissionStep(
    index: Int,
    title: String,
    rationale: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm)
                .clip(VaultTheme.shapes.card)
                .background(colors.surface)
                .padding(spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Step $index — $title",
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (granted) {
                Icon(Icons.Filled.Check, contentDescription = "Granted", tint = colors.accent)
            }
        }
        Text(
            text = rationale,
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.xs),
        )
        if (!granted) {
            PillButton(
                text = "Go to setting",
                onClick = onGrant,
                modifier = Modifier.padding(top = spacing.md),
            )
        }
    }
}
