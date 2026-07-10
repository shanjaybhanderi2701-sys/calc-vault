package com.appblish.calculatorvault.vault

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-217/APP-336 DoD: the per-category empty state renders the motivational lockup
 * (folder-with-lock illustration + reassurance couplet) and a single primary "Go to hide"
 * CTA that fires the hide flow's `onHide` lambda. The old text-only strings are retired.
 */
@RunWith(AndroidJUnit4::class)
class CategoryEmptyStateDoDTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun goToHideCta_existsAndInvokesOnHide_whenFolderEmpty() {
        var hideInvocations = 0
        compose.setContent {
            CalculatorVaultTheme {
                EmptyFolderState(onGoToHide = { hideInvocations++ })
            }
        }

        // CTA node exists, reads "Go to hide", and is clickable.
        val cta = compose.onNodeWithTag("empty_go_to_hide")
        cta.assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Go to hide").assertIsDisplayed()

        // Reassurance couplet is present.
        compose.onNodeWithText("Hide private files here\nNo one else can see").assertIsDisplayed()

        // Tapping the CTA fires the existing hide lambda exactly once.
        cta.performClick()
        assertThat(hideInvocations).isEqualTo(1)
    }

    @Test
    fun retiredStrings_noLongerRender() {
        compose.setContent {
            CalculatorVaultTheme {
                EmptyFolderState(onGoToHide = {})
            }
        }

        // Old copy ("No Hidden … Yet" / "Tap + to hide …") is gone.
        compose.onAllNodesWithText("No Hidden", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Tap + to hide", substring = true).assertCountEquals(0)
    }
}
