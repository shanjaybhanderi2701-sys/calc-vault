package com.appblish.calculatorvault.calculator

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-242 DoD: a wrong 4-digit PIN + `=` on the calculator disguise clears the entered
 * digits and shakes the display — and shows NOTHING disguise-breaking (no dialog, banner,
 * or error text). The screen stays on the calculator and never unlocks.
 */
@RunWith(AndroidJUnit4::class)
class WrongPinFeedbackDoDTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wrongPinClearsDigitsWithoutDisguiseBreakingUi() {
        // No configured credential resolves, so every 4-digit code is a wrong PIN.
        val vm = CalculatorViewModel(resolvePin = { null })
        var unlocked = false

        compose.setContent {
            CalculatorVaultTheme {
                CalculatorScreen(onUnlock = { _, _ -> unlocked = true }, viewModel = vm)
            }
        }

        // Match the keypad key (clickable), not the display text that echoes the digit.
        repeat(4) { compose.onNode(hasText("9") and hasClickAction()).performClick() }
        compose.onNodeWithText("9999").assertExists()

        compose.onNode(hasText("=") and hasClickAction()).performClick()
        compose.waitForIdle()

        // Digits cleared: the typed code is gone and the display fell back to "0"
        // (which now appears twice — the display plus the keypad's 0 key).
        compose.onAllNodesWithText("9999").assertCountEquals(0)
        compose.onAllNodesWithText("0").assertCountEquals(2)

        // The shake fired exactly once and we never left the calculator.
        assertThat(vm.uiState.value.pinRejections).isEqualTo(1)
        assertThat(unlocked).isFalse()

        // Nothing disguise-breaking anywhere in the tree.
        listOf("wrong", "incorrect", "invalid", "error", "try again").forEach { term ->
            compose.onAllNodesWithText(term, substring = true, ignoreCase = true).assertCountEquals(0)
        }
    }
}
