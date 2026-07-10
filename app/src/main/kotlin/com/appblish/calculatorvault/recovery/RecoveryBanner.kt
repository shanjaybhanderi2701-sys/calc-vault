package com.appblish.calculatorvault.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.VaultSession
import kotlinx.coroutines.launch

/**
 * The persistent "recovery not set up" warning banner (PIN Recovery spec §4, W0 screen 07),
 * pinned above the album/photo/video grids (ruling R2). Tapping the banner body opens setup
 * ([onSetUp]); "Later" hides it for the session ([onLater]) — it returns on the next launch,
 * and disappears permanently once recovery is configured.
 *
 * This is the app's one at-risk-red surface in the happy path: a forgotten PIN without
 * recovery means permanent data loss, so red is used deliberately (consistent with the
 * app's "red = risk/destructive only" rule). It only ever renders inside the unlocked vault.
 */
@Composable
fun RecoveryWarningBanner(
    onSetUp: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    // W0 07 redline: fill rgba(239,68,68,.10), 1px border rgba(239,68,68,.45), text #FCA5A5.
    val atRiskText = Color(0xFFFCA5A5)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm)
                .background(colors.destructive.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .border(1.dp, colors.destructive.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .clickable(onClick = onSetUp)
                .testTag("recovery-banner")
                .padding(start = spacing.lg, end = spacing.sm, top = spacing.md, bottom = spacing.md),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = atRiskText,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(spacing.md))
        Text(
            text = "Set up PIN recovery — without it, a forgotten PIN means your files are permanently lost.",
            style = VaultTheme.typography.labelMedium,
            color = atRiskText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onLater, modifier = Modifier.testTag("recovery-banner-later")) {
            Text(text = "Later", style = VaultTheme.typography.labelLarge, color = colors.textSecondary)
        }
    }
}

/**
 * Stateful wrapper that decides whether the grid banner (07) should show and renders it, so
 * grid screens only need to drop this in and pass [onSetUp]. It shows only when: the screen
 * is [eligible] (an album/photo/video grid — ruling R2), the session is the **real** vault
 * (never a decoy), recovery is not configured, and it hasn't been dismissed this session.
 * "Later" sets the session flag (returns next launch); configured status is re-checked on
 * every resume, so it disappears the moment setup completes.
 */
@Composable
fun RecoveryGridBanner(
    onSetUp: () -> Unit,
    eligible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!eligible || VaultSession.namespace.isNotEmpty()) return
    val scope = rememberCoroutineScope()
    val manager = remember { RecoveryGraph.recoveryManager }
    // Assume configured until the first check resolves, so the at-risk banner never flashes.
    var configured by remember { mutableStateOf(true) }
    var dismissed by remember { mutableStateOf(RecoveryPromptState.bannerDismissedThisSession) }
    LifecycleResumeEffect(Unit) {
        // Fail toward showing the at-risk banner: if the survive-uninstall keyfile check throws
        // (e.g. a transient read error) it must not leave `configured` stuck at its optimistic
        // `true` and silently hide the "recovery not set up" warning (APP-338).
        val job = scope.launch { configured = runCatching { manager.isConfigured() }.getOrDefault(false) }
        onPauseOrDispose { job.cancel() }
    }
    if (configured || dismissed) return
    RecoveryWarningBanner(
        onSetUp = onSetUp,
        onLater = {
            dismissed = true
            RecoveryPromptState.bannerDismissedThisSession = true
        },
        modifier = modifier,
    )
}
