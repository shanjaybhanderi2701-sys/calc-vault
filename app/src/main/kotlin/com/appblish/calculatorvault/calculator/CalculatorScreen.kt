package com.appblish.calculatorvault.calculator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The disguise. A plausible four-function calculator. The ONLY thing that makes it
 * special is that pressing '=' on the secret code raises [onUnlock] instead of
 * showing a number — see [CalculatorViewModel]. Layout here is a functional
 * placeholder; the polished grid comes from the approved wireframe (APP-142).
 */
@Composable
fun CalculatorScreen(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalculatorViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate exactly once per unlock request; do it as an effect, not during
    // composition, so recomposition can't fire the callback multiple times.
    LaunchedEffect(state.unlockRequested) {
        if (state.unlockRequested) {
            viewModel.onUnlockHandled()
            onUnlock()
        }
    }

    val rows =
        remember {
            listOf(
                listOf("7", "8", "9", "÷"),
                listOf("4", "5", "6", "×"),
                listOf("1", "2", "3", "-"),
                listOf("C", "0", "=", "+"),
            )
        }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = state.input.ifEmpty { "0" },
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = state.result,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { label ->
                        Button(
                            onClick = { onKey(viewModel, label) },
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        ) {
                            Text(text = label)
                        }
                    }
                }
            }
        }
    }
}

private fun onKey(
    viewModel: CalculatorViewModel,
    label: String
) {
    when (label) {
        "C" -> viewModel.onClear()
        "=" -> viewModel.onEquals()
        else -> viewModel.onDigit(label)
    }
}
