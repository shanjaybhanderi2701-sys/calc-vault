package com.appblish.calculatorvault.recovery

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.calculator.CalculatorScreen
import com.appblish.calculatorvault.calculator.CalculatorViewModel
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.VaultSession
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PIN Recovery W2 (APP-324) Definition-of-Done, on-device:
 *  - the `11223344 =` gate OPENS the recovery screen and resets nothing (spec §1.4);
 *  - three failed PIN attempts raise the "try another way" affordance, which opens recovery;
 *  - the grid banner shows while recovery is unconfigured, "Later" hides it for the session,
 *    and a configured vault never shows it.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryDoorwayBannerDoDTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        // Real-vault session so the banner is eligible; clean nag flags per test.
        VaultSession.begin("1234", "")
        RecoveryPromptState.reset()
    }

    @After
    fun tearDown() {
        RecoveryGraph.override(InMemoryRecoveryManager(configured = false))
        VaultSession.clear()
    }

    private fun tapKey(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }

    @Test
    fun elevenTwoTwoThreeThreeFourFourGateOpensRecoveryOnly() {
        var openedRecovery = false
        var unlocked = false
        val vm = CalculatorViewModel(resolvePin = { null })
        compose.setContent {
            CalculatorVaultTheme {
                CalculatorScreen(
                    onUnlock = { _, _ -> unlocked = true },
                    onOpenRecovery = { openedRecovery = true },
                    viewModel = vm,
                )
            }
        }

        "11223344".forEach { tapKey(it.toString()) }
        tapKey("=")
        compose.waitForIdle()

        assertThat(openedRecovery).isTrue()
        // Opens only — the doorway never unlocks a vault and never resets anything.
        assertThat(unlocked).isFalse()
    }

    @Test
    fun threeFailedAttemptsRevealAffordanceThatOpensRecovery() {
        var openedRecovery = false
        val vm = CalculatorViewModel(resolvePin = { null })
        compose.setContent {
            CalculatorVaultTheme {
                CalculatorScreen(
                    onUnlock = { _, _ -> },
                    onOpenRecovery = { openedRecovery = true },
                    viewModel = vm,
                )
            }
        }

        repeat(3) {
            "0000".forEach { tapKey(it.toString()) }
            tapKey("=")
            compose.waitForIdle()
        }

        compose.onNodeWithTag("recovery-affordance").assertIsDisplayed()
        compose.onNodeWithTag("recovery-affordance").performClick()
        compose.waitForIdle()
        assertThat(openedRecovery).isTrue()
    }

    @Test
    fun gridBannerShowsWhenUnconfiguredAndLaterHidesItForTheSession() {
        RecoveryGraph.override(InMemoryRecoveryManager(configured = false))
        compose.setContent {
            CalculatorVaultTheme {
                RecoveryGridBanner(onSetUp = {}, eligible = true)
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("recovery-banner").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("recovery-banner").assertIsDisplayed()

        compose.onNodeWithTag("recovery-banner-later").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag("recovery-banner").assertCountEquals(0)
        assertThat(RecoveryPromptState.bannerDismissedThisSession).isTrue()
    }

    @Test
    fun gridBannerHiddenWhenRecoveryConfigured() {
        RecoveryGraph.override(InMemoryRecoveryManager(configured = true))
        compose.setContent {
            CalculatorVaultTheme {
                RecoveryGridBanner(onSetUp = {}, eligible = true)
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("recovery-banner").assertCountEquals(0)
    }
}
