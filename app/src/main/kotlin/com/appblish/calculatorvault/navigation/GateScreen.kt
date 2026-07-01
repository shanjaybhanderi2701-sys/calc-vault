package com.appblish.calculatorvault.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The launch splash. It shows the CalcVault mark while deciding where to go: a first-run
 * user (no PIN set / onboarding incomplete) is routed to [onOnboard]; a returning user goes
 * straight to the calculator disguise via [onReady]. The check reads the encrypted store,
 * so it is done in an effect, not during composition.
 */
@Composable
fun GateScreen(
    onOnboard: () -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors

    LaunchedEffect(Unit) {
        if (AuthGraph.credentialStore.isOnboarded()) onReady() else onOnboard()
    }

    Column(
        modifier = modifier.fillMaxSize().background(colors.canvas),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.accent),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "CalcVault",
            style = VaultTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = VaultTheme.spacing.lg),
        )
    }
}
