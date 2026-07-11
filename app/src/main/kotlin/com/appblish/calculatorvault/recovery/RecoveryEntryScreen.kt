package com.appblish.calculatorvault.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The recovery-entry landing (PIN Recovery W0 screen 08): the single target both doorways
 * open — the `11223344 =` code and the 3-failed-attempt affordance. It offers the configured
 * recovery method(s) and reassures that files stay put; it never resets anything on its own
 * (spec §1.4). The actual unlock (09/10) and set-new-PIN (11) are W3 — [onAnswerMethod] /
 * [onCodeMethod] hand off to that flow.
 *
 * If recovery was never configured (a doorway reached before setup), it shows the honest
 * dead-end: no backdoor, any files still in the vault stay encrypted and locked (a compact form of W0 screen 13).
 */
@Composable
fun RecoveryEntryScreen(
    onAnswerMethod: () -> Unit,
    onCodeMethod: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryEntryViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.sm)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = colors.textPrimary)
            }
        }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = spacing.xl)) {
            if (state.loading) {
                return@Column
            }
            if (state.configured) {
                ConfiguredMethods(
                    question = state.question,
                    onAnswerMethod = onAnswerMethod,
                    onCodeMethod = onCodeMethod,
                )
            } else {
                UnconfiguredDeadEnd()
            }
        }
    }
}

@Composable
private fun ConfiguredMethods(
    question: String?,
    onAnswerMethod: () -> Unit,
    onCodeMethod: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Text(text = "Forgot your PIN?", style = VaultTheme.typography.headlineSmall, color = colors.textPrimary)
    Spacer(Modifier.height(spacing.md))
    Text(
        text =
            "Choose a way to prove it's you. You'll set a new PIN afterward — your hidden " +
                "files stay exactly where they are.",
        style = VaultTheme.typography.bodyMedium,
        color = colors.textSecondary,
    )
    Spacer(Modifier.height(spacing.xl))
    MethodCard(
        icon = Icons.Filled.Person,
        title = "Answer security question",
        subtitle = question ?: "Your security question",
        onClick = onAnswerMethod,
        testTag = "recovery-method-answer",
    )
    Spacer(Modifier.height(spacing.md))
    MethodCard(
        icon = Icons.Filled.Lock,
        title = "Enter recovery code",
        subtitle = "The 16-character code you saved at setup",
        onClick = onCodeMethod,
        testTag = "recovery-method-code",
    )
}

@Composable
private fun MethodCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .testTag(testTag)
                .padding(spacing.lg),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.accent)
        Column(modifier = Modifier.weight(1f).padding(start = spacing.md)) {
            Text(text = title, style = VaultTheme.typography.bodyLarge, color = colors.textPrimary)
            Text(text = subtitle, style = VaultTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary)
    }
}

@Composable
private fun UnconfiguredDeadEnd() {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(bottom = spacing.xxl),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(top = spacing.xxl),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.destructive,
                modifier = Modifier.testTag("recovery-unconfigured"),
            )
        }
        Text(
            text = "Recovery isn't set up",
            style = VaultTheme.typography.headlineSmall,
            color = colors.textPrimary,
        )
        Text(
            text =
                "Without a security answer or recovery code, a forgotten PIN can't be reset by " +
                    "anyone — including us. There's no backdoor by design. Any files still in the " +
                    "vault stay encrypted and locked.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}
