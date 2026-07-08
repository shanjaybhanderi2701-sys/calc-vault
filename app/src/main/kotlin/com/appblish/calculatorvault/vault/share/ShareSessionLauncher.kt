package com.appblish.calculatorvault.vault.share

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * The one place a prepared [VaultShare.Session] meets the system share sheet (APP-294):
 * when [request] becomes non-null the chooser is launched exactly once ([onLaunched]
 * consumes the request so recomposition/config-change can never re-fire it), and the
 * activity-result callback — the moment the chooser/receiver flow hands control back,
 * whether the share completed or was cancelled — invokes [onFinished], whose owner
 * purges the session's temp copies. Both the viewer and the multi-select tray route
 * their share flows through this so the delete-after-share contract has a single seam.
 */
@Composable
fun ShareSessionLauncher(
    request: VaultShare.Session?,
    onLaunched: () -> Unit,
    onFinished: () -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onFinished()
        }
    LaunchedEffect(request) {
        val session = request ?: return@LaunchedEffect
        // Consume before launching: a recomposition mid-launch must never double-fire.
        onLaunched()
        launcher.launch(VaultShare.chooserIntent(session))
    }
}
