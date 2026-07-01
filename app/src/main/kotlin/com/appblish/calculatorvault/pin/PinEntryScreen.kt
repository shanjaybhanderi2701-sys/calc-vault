package com.appblish.calculatorvault.pin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Second gate after the calculator secret trigger. For the scaffold this is a
 * placeholder that simply confirms; the real PIN pad, attempt throttling, and
 * biometric fallback are wired against the approved wireframe (APP-142) and the
 * vault crypto milestone.
 */
@Composable
fun PinEntryScreen(
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Enter PIN", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onAuthenticated) { Text(text = "Unlock (stub)") }
        Button(onClick = onDismiss) { Text(text = "Cancel") }
    }
}
