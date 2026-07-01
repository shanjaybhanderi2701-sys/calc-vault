package com.appblish.calculatorvault.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * A single onboarding intro card, reused for both of the deck's closing screens — "Private
 * Apps & Media Vault" (Next) and "Custom App Icons" (Done). A "Skip" affordance sits top
 * right; a two-dot page indicator shows progress; a single green pill is the primary CTA.
 */
@Composable
fun IntroCardScreen(
    title: String,
    body: String,
    icon: ImageVector,
    ctaLabel: String,
    pageIndex: Int,
    pageCount: Int,
    onCta: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.canvas)
                .padding(horizontal = spacing.xl, vertical = spacing.lg),
    ) {
        Text(
            text = "Skip",
            style = VaultTheme.typography.titleMedium,
            color = colors.textSecondary,
            modifier =
                Modifier
                    .align(Alignment.End)
                    .clip(VaultTheme.shapes.pill)
                    .clickable(onClick = onSkip)
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
        )

        Spacer(Modifier.weight(1f))

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(120.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(colors.accent.copy(alpha = 0.16f)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(56.dp),
            )
        }

        Text(
            text = title,
            style = VaultTheme.typography.headlineMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xl),
        )
        Text(
            text = body,
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
        )

        Spacer(Modifier.weight(1f))

        PageDots(pageIndex = pageIndex, pageCount = pageCount)

        PillButton(
            text = ctaLabel,
            onClick = onCta,
            modifier = Modifier.padding(top = spacing.xl),
        )
    }
}

@Composable
private fun PageDots(
    pageIndex: Int,
    pageCount: Int,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(pageCount) { index ->
            val active = index == pageIndex
            Box(
                modifier =
                    Modifier
                        .height(8.dp)
                        .width(if (active) 20.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (active) colors.accent else colors.surfaceVariant),
            )
        }
    }
}
