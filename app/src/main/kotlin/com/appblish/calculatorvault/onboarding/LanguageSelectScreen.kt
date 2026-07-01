package com.appblish.calculatorvault.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * First onboarding screen — the App Language list from the deck. "Default" (follow system)
 * sits at the top pre-selected; a compact green "Done" pill confirms. Selecting a row moves
 * the radio; it does not advance — only "Done" does.
 */
@Composable
fun LanguageSelectScreen(
    selected: String,
    onSelect: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.lg),
        ) {
            Text(
                text = "App Language",
                style = VaultTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Surface(
                color = colors.accent,
                contentColor = colors.onAccent,
                shape = VaultTheme.shapes.pill,
                modifier = Modifier.clickable(onClick = onDone),
            ) {
                Text(
                    text = "Done",
                    style = VaultTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(SUPPORTED_LANGUAGES) { language ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(language) }
                            .padding(horizontal = spacing.lg, vertical = spacing.sm),
                ) {
                    Text(
                        text = language,
                        style = VaultTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    RadioButton(
                        selected = language == selected,
                        onClick = { onSelect(language) },
                        colors =
                            RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.textSecondary,
                            ),
                    )
                }
            }
        }
    }
}
