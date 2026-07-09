package com.appblish.calculatorvault.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * W3 seam (PIN Recovery W0 09/10 → 11). W2 delivers the doorways + the recovery-entry landing
 * (08); the actual unlock (unwrap via Wrap B/C) and set-new-PIN (re-wrap Wrap A) are W3's
 * Security-gated deliverable (APP-325). This placeholder keeps the entry-screen method rows
 * from dead-ending until W3 replaces it with the real flow at the same route.
 *
 * It deliberately performs NO crypto and never resets anything (spec §1.4). [method] is
 * `answer` or `code` — the path the user chose on 08, which W3 will branch on.
 */
@Composable
fun RecoveryUnlockPlaceholderScreen(
    method: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.sm)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = colors.textPrimary)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(start = spacing.xl, end = spacing.xl, bottom = spacing.xxl),
        ) {
            Text(
                text = if (method == "code") "Enter recovery code" else "Answer security question",
                style = VaultTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.testTag("recovery-unlock-placeholder"),
            )
            Text(
                text = "This step is being finalized. You'll verify here and then set a new PIN — " +
                    "your hidden files stay exactly where they are.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
    }
}
