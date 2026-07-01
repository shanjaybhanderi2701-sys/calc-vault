package com.appblish.calculatorvault.vault

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The landing shown once a PIN unlocks a vault. Phase 1 is the auth spine, so this is an
 * intentionally minimal placeholder — the full "CalcVault" dashboard (categories, tools,
 * AppLock) is built in Phase 2. Its job here is to make the disguise → PIN → vault path
 * demonstrable and to prove the Fake Password guarantee visibly: the real PIN lands on the
 * real vault (with the Fake Password manager), a decoy PIN lands on a clearly separate
 * decoy space that offers no path back to the real one.
 */
@Composable
fun UnlockedVaultScreen(
    kind: VaultKind,
    onManageFakePasswords: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val isReal = kind is VaultKind.Real

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
                imageVector = if (isReal) Icons.Filled.Lock else Icons.Filled.Person,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(44.dp),
            )
        }

        Text(
            text = if (isReal) "CalcVault" else "Decoy space",
            style = VaultTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = spacing.xl),
        )
        Text(
            text =
                if (isReal) {
                    "Your private vault is unlocked. The media categories and tools land here in Phase 2."
                } else {
                    "This is a separate decoy vault. Nothing here touches your real, hidden content."
                },
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
        )
        Text(
            text = "Storage: ${kind.storageId}",
            style = VaultTheme.typography.labelMedium,
            color = colors.textDisabled,
            modifier = Modifier.padding(top = spacing.sm),
        )

        if (isReal) {
            PillButton(
                text = "Fake Password",
                onClick = onManageFakePasswords,
                style = PillButtonStyle.Secondary,
                modifier = Modifier.padding(top = spacing.xxl),
            )
            PillButton(
                text = "Lock",
                onClick = onLock,
                modifier = Modifier.padding(top = spacing.md),
            )
        } else {
            PillButton(
                text = "Lock",
                onClick = onLock,
                modifier = Modifier.padding(top = spacing.xxl),
            )
        }
    }
}
