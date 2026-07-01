package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** Role of a calculator key — drives its color per the deck. */
enum class CalcKeyStyle {
    /** Digits and `.` — neutral surface, white glyph. */
    Digit,

    /** `÷ × − +` operators — green accent. */
    Operator,

    /** `AC` / `( )` / `%` — muted surface-variant. */
    Function,

    /** `AC` on the disguise keypad — transparent with a green ring + green glyph (deck). */
    Clear,

    /** `=` — filled green, the secret-unlock trigger. */
    Equals,
}

/**
 * One circular calculator key from the disguise keypad. Square by aspect ratio so a
 * uniform grid of keys stays perfectly round regardless of available width.
 */
@Composable
fun CalculatorKey(
    label: String,
    style: CalcKeyStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val container: Color =
        when (style) {
            CalcKeyStyle.Digit -> colors.surface
            CalcKeyStyle.Operator -> colors.surfaceVariant
            CalcKeyStyle.Function -> colors.surfaceVariant
            CalcKeyStyle.Clear -> colors.canvas
            CalcKeyStyle.Equals -> colors.accent
        }
    val content: Color =
        when (style) {
            CalcKeyStyle.Digit -> colors.textPrimary
            CalcKeyStyle.Operator -> colors.accent
            CalcKeyStyle.Function -> colors.textSecondary
            CalcKeyStyle.Clear -> colors.accent
            CalcKeyStyle.Equals -> colors.onAccent
        }

    val base =
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(container)
    val shaped = if (style == CalcKeyStyle.Clear) base.border(1.5.dp, colors.accent, CircleShape) else base

    Box(
        contentAlignment = Alignment.Center,
        modifier = shaped.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = VaultTheme.typography.headlineSmall,
            color = content,
        )
    }
}
