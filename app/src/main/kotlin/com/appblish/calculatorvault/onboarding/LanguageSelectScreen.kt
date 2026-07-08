package com.appblish.calculatorvault.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

/**
 * S3 "Setting Up Language" loader: a modal card over the dimmed language list shown while
 * [OnboardingViewModel] holds `applyingLanguage`. The scrim consumes taps (indication-less
 * no-op clickable) so the list and Done pill beneath cannot be hit; three accent dots pulse
 * in sequence to read as activity without pulling in any extra dependencies.
 */
@Composable
internal fun LanguageApplyingOverlay(modifier: Modifier = Modifier) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    // One looping 0→3 ramp; each dot brightens while the ramp passes its index.
    val transition = rememberInfiniteTransition(label = "languageApplyDots")
    val ramp by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "languageApplyRamp",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
    ) {
        Surface(
            color = colors.surface,
            shape = VaultTheme.shapes.card,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.xl),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    repeat(3) { index ->
                        val active = ramp.toInt().coerceIn(0, 2) == index
                        Box(
                            modifier =
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (active) colors.accent else colors.accent.copy(alpha = 0.35f)),
                        )
                    }
                }
                Text(
                    text = "Setting Up Language",
                    style = VaultTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(top = spacing.lg),
                )
                Text(
                    text = "Applying your language preference…",
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}
