package com.appblish.calculatorvault.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * Simple vault-wide search (docx image27 home header / spec §3): a live name filter over
 * every hidden item, tapping a result opens the viewer. Deliberately minimal for Phase 1 —
 * the header icon must exist per the docx; a fuller search UX is a later phase.
 */
@Composable
fun VaultSearchScreen(
    onBack: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var query by remember { mutableStateOf("") }
    val allItems by
        remember { VaultGraph.contentRepository.allItems() }
            .collectAsState(initial = emptyList())

    val results =
        if (query.isBlank()) {
            emptyList()
        } else {
            allItems.filter { it.originalName.contains(query, ignoreCase = true) }
        }

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(end = spacing.lg, top = spacing.md),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search hidden files", color = colors.textSecondary) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary) },
                singleLine = true,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                    ),
                modifier = Modifier.weight(1f),
            )
        }

        if (query.isNotBlank() && results.isEmpty()) {
            Text(
                text = "No hidden files match \"$query\".",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(spacing.lg),
            )
        }

        LazyColumn(contentPadding = PaddingValues(vertical = spacing.sm)) {
            items(results, key = { it.id }) { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenItem(item) }
                            .padding(horizontal = spacing.lg, vertical = spacing.sm),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(VaultTheme.shapes.thumbnail)
                                .background(colors.surfaceVariant),
                    ) {
                        Icon(
                            imageVector = item.category.icon(),
                            contentDescription = null,
                            tint = item.category.color(),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = spacing.md)) {
                        Text(
                            text = item.originalName,
                            style = VaultTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "${item.category.label} · ${item.dateLabel}",
                            style = VaultTheme.typography.labelMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}
