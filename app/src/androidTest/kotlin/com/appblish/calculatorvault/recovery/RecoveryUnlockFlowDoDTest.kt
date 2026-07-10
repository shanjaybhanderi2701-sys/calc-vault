package com.appblish.calculatorvault.recovery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.crypto.RecoveryReKeyer
import com.appblish.calculatorvault.vault.crypto.RecoveryResetOutcome
import com.appblish.calculatorvault.vault.crypto.RecoveryVerifyOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PIN Recovery W3 (APP-325) Definition-of-Done, on-device UI: proving identity with the
 * recovery code, then setting a new PIN, walks the real [RecoveryUnlockScreen] from the
 * recovery-code entry through the new-PIN + confirm steps to completion — and the flow
 * unlocks the vault under the new PIN (VaultSession follows), never touching the files.
 *
 * The envelope crypto is proven on-device in
 * [com.appblish.calculatorvault.vault.RecoveryResetDoDTest]; this test injects a fake reset
 * seam so it can assert the screen wiring (steps, confirm, completion) deterministically.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryUnlockFlowDoDTest {
    @get:Rule
    val compose = createComposeRule()

    /** Always-correct reset seam so the test drives the screen wiring, not secret matching. */
    private val fakeReKeyer =
        object : RecoveryReKeyer {
            override suspend fun verify(
                method: RecoveryMethod,
                secret: String,
            ) = RecoveryVerifyOutcome.CORRECT

            override suspend fun resetPin(
                method: RecoveryMethod,
                secret: String,
                newPin: String,
            ) = RecoveryResetOutcome.RESET
        }

    @Before
    fun setUp() {
        RecoveryGraph.override(InMemoryRecoveryManager(configured = true))
        RecoveryGraph.overrideReKeyer(fakeReKeyer)
        RecoveryGraph.overrideAttemptStore(InMemoryRecoveryAttemptStore())
        VaultSession.clear()
    }

    @After
    fun tearDown() {
        RecoveryGraph.override(InMemoryRecoveryManager(configured = false))
        VaultSession.clear()
    }

    private fun tapKey(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun enterPin(pin: String) {
        pin.forEach { tapKey(it.toString()) }
        tapKey("=")
    }

    @Test
    fun recoveryCodeUnlockResetsThePinAndOpensTheVault() {
        var doneCalled = false
        compose.setContent {
            CalculatorVaultTheme {
                RecoveryUnlockScreen(
                    method = RecoveryMethod.RECOVERY_CODE,
                    onDone = { doneCalled = true },
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        // 09/10 — enter the recovery code and continue.
        compose.onNodeWithTag("recovery-unlock-secret-input").performTextInput("7K9F2XQP4MRT8WVN")
        compose.onNodeWithTag("recovery-unlock-submit").performClick()
        compose.waitForIdle()

        // 11 — set the new PIN, then confirm it.
        enterPin("5678")
        compose.waitForIdle()
        enterPin("5678")
        compose.waitForIdle()

        // Completion — the screen reports done and hands off to the unlocked vault.
        compose.onNodeWithTag("recovery-unlock-done").assertIsDisplayed()
        assertThat(doneCalled).isTrue()
        assertThat(VaultSession.passphrase).isEqualTo("5678")
    }
}
