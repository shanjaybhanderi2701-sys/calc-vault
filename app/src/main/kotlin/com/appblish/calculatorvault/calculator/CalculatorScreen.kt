package com.appblish.calculatorvault.calculator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The disguise — a fully functional calculator that is the app's front door. Non-secret
 * input behaves exactly like a pocket calculator; typing a configured 4-digit code and
 * pressing `=` raises [onUnlock] with the vault that code opens (real or a decoy) and the
 * code itself (the vault passphrase).
 *
 * PIN Recovery (APP-321) adds two disguise-safe doorways that raise [onOpenRecovery] — a
 * pure navigation signal that resets nothing (spec §1.4): typing the fixed `11223344` code
 * and pressing `=`, and a single low-emphasis "try another way" line that appears **only**
 * after 3 failed PIN attempts (spec §3.2, W0 screen 12). At rest the screen still hints at
 * nothing — no "Forgot PIN?", no recovery affordance (W0 screen 16).
 */
@Composable
fun CalculatorScreen(
    onUnlock: (kind: VaultKind, code: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenRecovery: () -> Unit = {},
    viewModel: CalculatorViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors

    // Fire the unlock exactly once per request, as an effect rather than during
    // composition so recomposition can't re-trigger navigation. The matched code is
    // forwarded as the vault passphrase.
    LaunchedEffect(state.unlock) {
        val kind = state.unlock
        if (kind != null) {
            val code = state.unlockCode
            viewModel.onUnlockHandled()
            onUnlock(kind, code)
        }
    }

    // Open the recovery screen exactly once per doorway request (spec §1.4 — opens only).
    LaunchedEffect(state.openRecovery) {
        if (state.openRecovery) {
            viewModel.onRecoveryHandled()
            onOpenRecovery()
        }
    }

    CalculatorKeypad(
        display = state.display.ifEmpty { "0" },
        onKey = viewModel::onToken,
        modifier = modifier,
        shakeTrigger = state.pinRejections,
        belowDisplay =
            if (state.showRecoveryAffordance) {
                {
                    // W0 screen 12: one low-emphasis line, the green link only on the action
                    // words. Appears solely after the 3rd fail — never a pre-emptive hint.
                    Text(
                        text =
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = colors.textSecondary)) {
                                    append("Trouble signing in? ")
                                }
                                withStyle(SpanStyle(color = colors.accent)) {
                                    append("Try another way")
                                }
                            },
                        style = VaultTheme.typography.bodyMedium,
                        modifier =
                            Modifier
                                .testTag("recovery-affordance")
                                .clickable { viewModel.openRecoveryScreen() }
                                .padding(vertical = VaultTheme.spacing.sm),
                    )
                }
            } else {
                null
            },
    )
}
