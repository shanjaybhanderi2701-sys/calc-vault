package com.appblish.calculatorvault.vault.storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The contextual All-Files-Access primer (spec §5; design call D-2 on APP-224, docx
 * image13): a modal bottom sheet over the dimmed vault, shown on the **first tap into a
 * content surface** (category / bin / recent / hide attempt) while the grant is missing —
 * never at launch. Calm native-trust copy, no scare framing. "Allow" hands off to the
 * system All-Files-Access settings via [onAllow]; Cancel/scrim-tap dismisses and the
 * surface stays gated (spec §5 denial behavior).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllFilesPrimerSheet(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg)
                    .padding(bottom = spacing.xl),
        ) {
            PhoneToggleIllustration()

            Text(
                text = "Allow File Access",
                style = VaultTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = spacing.lg),
            )
            Text(
                text =
                    "Allow access to securely hide and protect your private files — " +
                        "nothing is uploaded.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.sm, bottom = spacing.lg),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                PillButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    style = PillButtonStyle.Secondary,
                    modifier = Modifier.weight(0.4f),
                )
                PillButton(
                    text = "Allow",
                    onClick = onAllow,
                    style = PillButtonStyle.Primary,
                    modifier = Modifier.weight(0.6f),
                )
            }
        }
    }
}

/**
 * The image13 illustration: a stylized phone face showing the system "Allow all file
 * access" toggle flipped on, drawn with plain shapes so it needs no raster asset.
 */
@Composable
private fun PhoneToggleIllustration() {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Surface(
        color = colors.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.width(180.dp),
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            // Camera notch dot.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.canvas),
            )
            Text(
                text = "All File Access",
                style = VaultTheme.typography.labelLarge,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = spacing.md),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm, bottom = spacing.sm),
            ) {
                Text(
                    text = "Allow all file access",
                    style = VaultTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.sm))
                Switch(
                    checked = true,
                    onCheckedChange = null,
                    colors =
                        SwitchDefaults.colors(
                            checkedTrackColor = colors.accent,
                            checkedThumbColor = colors.onAccent,
                        ),
                )
            }
            // Faint list rows suggesting the rest of the system screen.
            repeat(2) {
                Box(
                    modifier =
                        Modifier
                            .padding(top = spacing.xs)
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.canvas),
                )
            }
        }
    }
}
