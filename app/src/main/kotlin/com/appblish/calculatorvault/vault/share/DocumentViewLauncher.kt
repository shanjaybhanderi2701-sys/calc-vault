package com.appblish.calculatorvault.vault.share

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * The one place a prepared single-item [VaultShare.Session] meets the system **document
 * viewer** (APP-527 §4). Sibling of [ShareSessionLauncher], but it launches
 * [VaultShare.viewIntent] (`ACTION_VIEW`, read-only grant) instead of the share chooser:
 * a Documents item tap decrypts one blob to `cacheDir/share/<uuid>/` and hands it to a real
 * installed viewer. The security contract is identical and reused wholesale — when [request]
 * becomes non-null the viewer is launched exactly once ([onLaunched] consumes it so
 * recomposition/config-change can't re-fire), and the activity-result callback — the moment
 * the viewer flow hands control back, whether the document was opened or the viewer was
 * cancelled — invokes [onFinished], whose owner purges the temp copy (guaranteed cleanup,
 * with [VaultShare.purgeAll] at process start as the crash/force-kill backstop).
 */
@Composable
fun DocumentViewLauncher(
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
        launcher.launch(VaultShare.viewIntent(session))
    }
}
