package com.appblish.calculatorvault.calculator

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * The hint-pill strings that sit above the calculator display during PIN capture, matching
 * the board's frames. The emphasised fragments ("4-digit", `=`) are drawn in the green
 * accent exactly as the deck renders them.
 */
@Composable
fun createPinHint(): AnnotatedString = emphasise("Create a 4-digit password, then tap \"=\"", "4-digit", "\"=\"")

@Composable
fun confirmPinHint(): AnnotatedString = emphasise("Re-enter your 4-digit password to continue", "4-digit")

@Composable
fun changePinHint(): AnnotatedString = emphasise("Enter a new 4-digit password, then tap \"=\"", "4-digit", "\"=\"")

@Composable
private fun emphasise(
    text: String,
    vararg accented: String,
): AnnotatedString {
    val accent = VaultTheme.colors.accent
    return buildAnnotatedString {
        append(text)
        for (fragment in accented) {
            var from = text.indexOf(fragment)
            while (from >= 0) {
                addStyle(SpanStyle(color = accent), from, from + fragment.length)
                from = text.indexOf(fragment, from + fragment.length)
            }
        }
    }
}
