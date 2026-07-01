package com.appblish.calculatorvault.calculator

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.appblish.calculatorvault.ui.components.CalcKeyStyle
import com.appblish.calculatorvault.ui.components.CalculatorKey
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The disguise keypad, rendered exactly as the board's calculator frame: a right-aligned
 * numeral display over the 5×4 grid `AC ( ) % ÷ / 7 8 9 × / 4 5 6 − / 1 2 3 + / . 0 ⌫ =`.
 * Every key is a white glyph on a dark circle except `AC` (green ring) and `=` (green
 * fill), which are the only two accented keys in the frame.
 *
 * This one composable backs every mode that types on the calculator — the real unlock
 * surface, onboarding PIN create/confirm, change-password, and the Fake Password setup —
 * so they are pixel-identical. Callers vary only the [title] (large-title header; `null`
 * for the pure disguise so it looks like nothing but a calculator), the [hint] pill, and
 * the [display] string. [onKey] receives the tapped [CalcToken].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalculatorKeypad(
    display: String,
    onKey: (CalcToken) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    hint: AnnotatedString? = null,
    onBack: (() -> Unit)? = null,
    onDisplayLongPress: (() -> Unit)? = null,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.canvas)
                .padding(horizontal = spacing.lg),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.sm)) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary,
                        )
                    }
                }
                Text(
                    text = title,
                    style = VaultTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                )
            }
        }

        Spacer(Modifier.weight(0.4f))

        if (hint != null) {
            Surface(
                color = colors.surface,
                shape = VaultTheme.shapes.pill,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = hint,
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                )
            }
            Spacer(Modifier.weight(0.2f))
        }

        val displayModifier =
            if (onDisplayLongPress != null) {
                // Hidden recovery gesture: long-pressing the display opens forgot-password.
                // Invisible so the disguise still reads as an ordinary calculator.
                Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = onDisplayLongPress,
                )
            } else {
                Modifier
            }
        Text(
            text = display,
            style = VaultTheme.typography.displayLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().then(displayModifier).padding(vertical = spacing.md),
        )

        Spacer(Modifier.weight(1f))

        KEY_ROWS.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                row.forEach { token ->
                    CalculatorKey(
                        label = token.label,
                        style = token.style,
                        onClick = { onKey(token) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.padding(bottom = spacing.lg))
    }
}

/** A single tappable key on the disguise keypad. */
enum class CalcToken(
    val label: String,
    val style: CalcKeyStyle,
) {
    CLEAR("AC", CalcKeyStyle.Clear),
    PAREN("( )", CalcKeyStyle.Digit),
    PERCENT("%", CalcKeyStyle.Digit),
    DIVIDE("÷", CalcKeyStyle.Digit),
    SEVEN("7", CalcKeyStyle.Digit),
    EIGHT("8", CalcKeyStyle.Digit),
    NINE("9", CalcKeyStyle.Digit),
    MULTIPLY("×", CalcKeyStyle.Digit),
    FOUR("4", CalcKeyStyle.Digit),
    FIVE("5", CalcKeyStyle.Digit),
    SIX("6", CalcKeyStyle.Digit),
    MINUS("−", CalcKeyStyle.Digit),
    ONE("1", CalcKeyStyle.Digit),
    TWO("2", CalcKeyStyle.Digit),
    THREE("3", CalcKeyStyle.Digit),
    PLUS("+", CalcKeyStyle.Digit),
    DOT(".", CalcKeyStyle.Digit),
    ZERO("0", CalcKeyStyle.Digit),
    BACKSPACE("⌫", CalcKeyStyle.Digit),
    EQUALS("=", CalcKeyStyle.Equals),
    ;

    /** The character this key appends to the expression, or null for non-input keys. */
    val input: Char?
        get() =
            when (this) {
                CLEAR, BACKSPACE, EQUALS -> null
                PAREN -> '('
                else -> label.first()
            }
}

private val KEY_ROWS: List<List<CalcToken>> =
    listOf(
        listOf(CalcToken.CLEAR, CalcToken.PAREN, CalcToken.PERCENT, CalcToken.DIVIDE),
        listOf(CalcToken.SEVEN, CalcToken.EIGHT, CalcToken.NINE, CalcToken.MULTIPLY),
        listOf(CalcToken.FOUR, CalcToken.FIVE, CalcToken.SIX, CalcToken.MINUS),
        listOf(CalcToken.ONE, CalcToken.TWO, CalcToken.THREE, CalcToken.PLUS),
        listOf(CalcToken.DOT, CalcToken.ZERO, CalcToken.BACKSPACE, CalcToken.EQUALS),
    )
