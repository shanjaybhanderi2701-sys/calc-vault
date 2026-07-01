package com.appblish.calculatorvault.fakepassword

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.calculator.PinCalculatorScreen
import com.appblish.calculatorvault.calculator.confirmPinHint
import com.appblish.calculatorvault.calculator.createPinHint
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The Fake Password manager (Explore ▸ Fake Password in the deck). Lists the configured
 * decoy PINs — each opens its own separate decoy vault — and lets the owner add or remove
 * them. Adding runs the same calculator create/confirm the real PIN uses, titled "Fake
 * Password" per the board's editable frame.
 */
@Composable
fun FakePasswordScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FakePasswordViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.stage) {
        FakeStage.CREATE -> {
            val error = state.error
            PinCalculatorScreen(
                title = "Fake Password",
                hint = if (error != null) errorHint(error) else createPinHint(),
                onSubmit = viewModel::onCreated,
                onBack = viewModel::cancelAdd,
                modifier = modifier,
            )
        }

        FakeStage.CONFIRM ->
            PinCalculatorScreen(
                title = "Fake Password",
                hint = confirmPinHint(),
                onSubmit = viewModel::onConfirmed,
                onBack = viewModel::cancelAdd,
                modifier = modifier,
            )

        FakeStage.LIST ->
            DecoyList(
                slots = state.slots,
                onAdd = viewModel::startAdd,
                onRemove = viewModel::remove,
                onBack = onBack,
                modifier = modifier,
            )
    }
}

@Composable
private fun DecoyList(
    slots: List<Int>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.sm)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
                Text(
                    text = "Fake Password",
                    style = VaultTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                )
            }
            Text(
                text = "Each fake PIN opens a separate decoy vault, so a forced unlock never exposes your real one.",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            )

            if (slots.isEmpty()) {
                Text(
                    text = "No fake passwords yet. Tap + to add one.",
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(spacing.lg),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(slots) { slot ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            ListRow(
                                title = "Fake password ${slot + 1}",
                                subtitle = "Opens its own decoy vault",
                                leadingIcon = Icons.Filled.Lock,
                                leadingChipColor = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRemove(slot) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = colors.destructive)
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.lg),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add fake password")
        }
    }
}

@Composable
private fun errorHint(message: String): AnnotatedString {
    val destructive = VaultTheme.colors.destructive
    return buildAnnotatedString {
        pushStyle(SpanStyle(color = destructive))
        append(message)
        pop()
    }
}
