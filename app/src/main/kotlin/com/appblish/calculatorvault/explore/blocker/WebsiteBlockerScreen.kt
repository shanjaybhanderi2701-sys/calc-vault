package com.appblish.calculatorvault.explore.blocker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.explore.ToolEmptyState
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * Website Blocker. An add field at the top accepts a domain (scheme/www/path are stripped),
 * and each entry lists with a green enable toggle plus a red remove control (removal is the
 * only destructive action here). The Private Browser reads the same list and refuses to load
 * any enabled entry.
 */
@Composable
fun WebsiteBlockerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WebsiteBlockerViewModel = viewModel(),
) {
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun submit() {
        if (viewModel.add(input)) {
            input = ""
            error = false
        } else {
            error = true
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = "Website Blocker", onBack = onBack)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = false
                },
                singleLine = true,
                isError = error,
                placeholder = { Text("example.com") },
                supportingText = if (error) {
                    { Text("Enter a valid domain that isn't already blocked.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(spacing.sm))
            IconButton(onClick = { submit() }) {
                Icon(Icons.Filled.Add, contentDescription = "Add site", tint = colors.accent)
            }
        }

        if (sites.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ToolEmptyState(
                    title = "Nothing blocked yet",
                    message = "Add a site above. The Private Browser will refuse to open anything on this list.",
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sites, key = { it.id }) { site ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.md),
                    ) {
                        Text(
                            text = site.domain,
                            style = VaultTheme.typography.bodyLarge,
                            color = if (site.enabled) colors.textPrimary else colors.textDisabled,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = site.enabled,
                            onCheckedChange = { viewModel.setEnabled(site.id, it) },
                            colors =
                                SwitchDefaults.colors(
                                    checkedThumbColor = colors.onAccent,
                                    checkedTrackColor = colors.accent,
                                    uncheckedTrackColor = colors.surfaceVariant,
                                    uncheckedBorderColor = colors.divider,
                                ),
                        )
                        Spacer(Modifier.width(spacing.sm))
                        IconButton(onClick = { viewModel.remove(site.id) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove",
                                tint = colors.destructive,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(spacing.lg),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = "Blocking applies inside the Private Browser.",
                style = VaultTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
        }
    }
}
