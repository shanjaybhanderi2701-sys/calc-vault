package com.appblish.calculatorvault.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The "Allow File Access" onboarding step. Framed in native-trust language (per the
 * taste-guide) rather than a scare prompt: it explains *why* access is needed to hide and
 * protect files. "Allow" triggers the real permission request (the host wires it);
 * "Not Now" defers. Either choice advances the wizard.
 */
@Composable
fun AllowFileAccessScreen(
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
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
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.accent.copy(alpha = 0.16f)),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(44.dp),
            )
        }

        Text(
            text = "Allow File Access",
            style = VaultTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = spacing.xl),
        )
        Text(
            text =
                "Allow access to your photos, videos and files so CalcVault can " +
                    "hide and protect them in your private vault.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.md),
        )

        PillButton(
            text = "Allow",
            onClick = onAllow,
            modifier = Modifier.padding(top = spacing.xxl),
        )
        Text(
            text = "Not Now",
            style = VaultTheme.typography.titleMedium,
            color = colors.textSecondary,
            modifier =
                Modifier
                    .padding(top = spacing.lg)
                    .clip(VaultTheme.shapes.pill)
                    .clickable(onClick = onNotNow)
                    .padding(horizontal = spacing.lg, vertical = spacing.sm),
        )
    }
}
