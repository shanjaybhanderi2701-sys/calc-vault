package com.appblish.calculatorvault.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.VaultSession
import kotlinx.coroutines.launch

/**
 * The one-time recovery setup intro (PIN Recovery spec §2, ruling R1, W0 screen 01): a
 * bottom sheet offered once — right after the user's first vault operation — so the value is
 * concrete ("your first item is now hidden"). "Set up recovery" opens the setup flow;
 * "Maybe later" dismisses to the grid, where the persistent banner (07) takes over.
 *
 * Appears only inside the unlocked vault, never on the calculator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryIntroSheet(
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        modifier = modifier.testTag("recovery-intro-sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xl, vertical = spacing.lg),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(56.dp)
                        .background(colors.accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
            ) {
                Text(text = "🔑", fontSize = 28.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(spacing.lg))
            Text(text = "Set up PIN recovery", style = VaultTheme.typography.headlineSmall, color = colors.textPrimary)
            Spacer(Modifier.height(spacing.sm))
            Text(
                text =
                    "Your first item is now hidden. If you ever forget your PIN, recovery is the " +
                        "only way back in — we can't reset it for you. Takes about a minute.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(spacing.xl))
            PillButton(
                text = "Set up recovery",
                onClick = onSetUp,
                modifier = Modifier.testTag("recovery-intro-setup"),
            )
            Spacer(Modifier.height(spacing.sm))
            PillButton(text = "Maybe later", onClick = onDismiss, style = PillButtonStyle.Secondary)
            Spacer(Modifier.height(spacing.md))
        }
    }
}

/**
 * Decides when to offer the one-time setup intro (01) and hosts it, so the vault home only
 * needs to drop this in and pass [onSetUp]. Eligible when: the session is the real vault, the
 * user has completed their first vault operation
 * ([com.appblish.calculatorvault.auth.CredentialStore.hasOpenedRealVault]), recovery is not
 * configured, and the sheet hasn't already been offered this session. Either button marks it
 * offered so it never resurfaces this session; "Maybe later" leaves the grid banner (07) to
 * keep nagging.
 */
@Composable
fun RecoverySetupIntroHost(onSetUp: () -> Unit) {
    if (VaultSession.namespace.isNotEmpty()) return
    var show by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        val job =
            scope.launch {
                if (!RecoveryPromptState.introOfferedThisSession &&
                    AuthGraph.credentialStore.hasOpenedRealVault() &&
                    !RecoveryGraph.recoveryManager.isConfigured()
                ) {
                    show = true
                }
            }
        onPauseOrDispose { job.cancel() }
    }
    if (show) {
        RecoveryIntroSheet(
            onSetUp = {
                RecoveryPromptState.introOfferedThisSession = true
                show = false
                onSetUp()
            },
            onDismiss = {
                RecoveryPromptState.introOfferedThisSession = true
                show = false
            },
        )
    }
}
