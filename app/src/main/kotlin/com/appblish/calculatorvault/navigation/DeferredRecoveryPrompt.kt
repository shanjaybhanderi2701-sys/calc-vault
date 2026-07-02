package com.appblish.calculatorvault.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.onboarding.SecurityQuestionModal
import com.appblish.calculatorvault.vault.VaultSession
import kotlinx.coroutines.launch

/**
 * xlock-parity deferred recovery prompt (APP-212). The security recovery question is *not*
 * asked during onboarding. Instead this host, rendered over the real vault shell, offers it
 * only **after the first real vault operation**:
 *
 *  - First-ever real vault open → record the operation, prompt nothing (parity: no wall the
 *    moment they land).
 *  - Any subsequent real vault open with no recovery configured → surface the
 *    [SecurityQuestionModal]. "Cancel" defers and it is re-offered on the next open; "Save"
 *    persists the recovery material and it never shows again.
 *
 * Decoy vaults never see it — [VaultSession.namespace] is empty only for the real vault, so
 * the disguise spine and decoy isolation stay intact. A vault where recovery is already set
 * (e.g. configured from Settings) is likewise left alone.
 */
@Composable
fun DeferredRecoveryPrompt(store: CredentialStore = AuthGraph.credentialStore) {
    // Real vault only — decoys and the disguise never prompt for recovery.
    if (VaultSession.namespace.isNotEmpty()) return

    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (store.recoveryInfo() != null) return@LaunchedEffect
        if (store.hasOpenedRealVault()) {
            // The first real op already happened on a prior open — now it's time to offer.
            show = true
        } else {
            // This open *is* the first real vault operation; record it, prompt next time.
            store.markRealVaultOpened()
        }
    }

    if (show) {
        SecurityQuestionModal(
            onSave = { setup ->
                scope.launch {
                    store.setRecovery(setup)
                    show = false
                }
            },
            // Defer — recovery stays unset, so it is re-offered on the next real vault open.
            onCancel = { show = false },
        )
    }
}
