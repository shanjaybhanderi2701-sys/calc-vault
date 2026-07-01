package com.appblish.calculatorvault.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Landing surface once the vault is unlocked. Placeholder content only — the real
 * grid of protected items, categories, and empty states come from the approved
 * wireframe (APP-142).
 */
@Composable
fun VaultHomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Vault", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Your protected items will appear here.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
