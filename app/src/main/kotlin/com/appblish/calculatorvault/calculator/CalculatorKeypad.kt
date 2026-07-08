package com.appblish.calculatorvault.calculator

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
 *
 * [highlightEquals] draws a gentle scale pulse on the `=` key — the first-run "press = to
 * continue" cue (P3-1, APP-225 board feedback). It defaults to false and stays false for
 * the calculator disguise and every non-onboarding PIN surface, so the disguise never
 * animates.
 *
 * [shakeTrigger] plays a brief horizontal shake of the display each time its value
 * changes from a previous non-initial value — the minimal wrong-PIN feedback (APP-242).
 * Callers bump a counter per rejection; 0 (the default) never shakes.
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
    highlightEquals: Boolean = false,
    shakeTrigger: Int = 0,
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
        // APP-242 wrong-PIN feedback: a ~360ms damped left-right shake of the display,
        // re-armed on every bump of [shakeTrigger]. Offset is read inside graphicsLayer so
        // the shake redraws without recomposing; 0 means "never shaken", so a fresh
        // composition doesn't replay an old rejection.
        val shakeOffset = remember { Animatable(0f) }
        LaunchedEffect(shakeTrigger) {
            if (shakeTrigger == 0) return@LaunchedEffect
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec =
                    keyframes {
                        durationMillis = 360
                        -12f at 45
                        12f at 105
                        -8f at 165
                        8f at 225
                        -4f at 285
                        0f at 360
                    },
            )
        }
        Text(
            text = display,
            style = VaultTheme.typography.displayLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = shakeOffset.value.dp.toPx() }
                    .then(displayModifier)
                    .padding(vertical = spacing.md),
        )

        Spacer(Modifier.weight(1f))

        // P3-1 cue: a subtle 1f→1.08f breathing pulse on "=". The transition only exists
        // while the flag is up (onboarding create/confirm with a complete PIN); the scale is
        // read inside graphicsLayer, so the pulse redraws without recomposing the keypad.
        val equalsScale: State<Float>? =
            if (highlightEquals) {
                rememberInfiniteTransition(label = "equalsCue").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 550), RepeatMode.Reverse),
                    label = "equalsCueScale",
                )
            } else {
                null
            }

        KEY_ROWS.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                row.forEach { token ->
                    val pulse =
                        if (token == CalcToken.EQUALS && equalsScale != null) {
                            Modifier.graphicsLayer {
                                scaleX = equalsScale.value
                                scaleY = equalsScale.value
                            }
                        } else {
                            Modifier
                        }
                    CalculatorKey(
                        label = token.label,
                        style = token.style,
                        onClick = { onKey(token) },
                        modifier = Modifier.weight(1f).then(pulse),
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
