package com.appblish.calculatorvault.calculator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString

/** The vault's PINs are four digits, per the deck ("Create a 4-digit password…"). */
const val PIN_LENGTH: Int = 4

/**
 * A calculator surface driven as a PIN pad. It reuses [CalculatorKeypad] verbatim — the
 * board creates and confirms the PIN *on the calculator itself* — but constrains input to
 * digits: only `0-9` count (up to [PIN_LENGTH]), `⌫` deletes, `AC` clears, and `=` submits
 * once the code is complete. Operators/`.`/`( )`/`%` are inert here, exactly as the
 * create-PIN frame implies. The entered digits are shown as typed (the frame shows the raw
 * "1111"), not masked.
 *
 * This backs onboarding create/confirm, change-password, fake-password setup, and the
 * forgot-password reset, so all of them are visually identical to the unlock surface.
 *
 * [highlightEqualsWhenComplete] opts into the first-run "=" confirm cue (P3-1, APP-225
 * board feedback): once exactly [PIN_LENGTH] digits are entered, the `=` key pulses gently
 * to signal "press to continue". Only onboarding create/confirm passes true; every other
 * caller (and the calculator disguise, which never uses this screen) keeps the default
 * false and is pixel-identical to before.
 */
@Composable
fun PinCalculatorScreen(
    title: String?,
    hint: AnnotatedString?,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    highlightEqualsWhenComplete: Boolean = false,
) {
    var entry by remember { mutableStateOf("") }

    CalculatorKeypad(
        display = entry,
        title = title,
        hint = hint,
        onBack = onBack,
        modifier = modifier,
        highlightEquals = equalsCueActive(highlightEqualsWhenComplete, entry),
        onKey = { token ->
            when (token) {
                CalcToken.BACKSPACE -> entry = entry.dropLast(1)
                CalcToken.CLEAR -> entry = ""
                CalcToken.EQUALS -> {
                    if (entry.length == PIN_LENGTH) {
                        val submitted = entry
                        entry = ""
                        onSubmit(submitted)
                    }
                }
                else -> {
                    val ch = token.input
                    if (ch != null && ch.isDigit() && entry.length < PIN_LENGTH) {
                        entry += ch
                    }
                }
            }
        },
    )
}

/**
 * True when the `=` confirm cue should show: the caller opted in (first-run create/confirm
 * only) and [entry] holds a complete [PIN_LENGTH]-digit code. Pure, so the trigger
 * condition is unit-testable off-device.
 */
fun equalsCueActive(
    cueEnabled: Boolean,
    entry: String,
): Boolean = cueEnabled && entry.length == PIN_LENGTH
