package com.appblish.calculatorvault.calculator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.auth.VaultKind

/**
 * The disguise — a fully functional calculator that is the app's front door. Non-secret
 * input behaves exactly like a pocket calculator; typing a configured 4-digit code and
 * pressing `=` raises [onUnlock] with the vault that code opens (real or a decoy) and the
 * code itself (the vault passphrase). Nothing on this screen hints at the vault. There is
 * no recovery gesture in Phase 1 — all forgotten-PIN recovery is deferred (spec §0,
 * APP-225).
 */
@Composable
fun CalculatorScreen(
    onUnlock: (kind: VaultKind, code: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalculatorViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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

    CalculatorKeypad(
        display = state.display.ifEmpty { "0" },
        onKey = viewModel::onToken,
        modifier = modifier,
    )
}
