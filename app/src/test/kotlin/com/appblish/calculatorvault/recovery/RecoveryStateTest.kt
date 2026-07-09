package com.appblish.calculatorvault.recovery

import com.appblish.calculatorvault.vault.VaultSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * PIN Recovery W2 (APP-324) — the session-scoped nag flags and the in-memory manager guards.
 * The banner "Later" / intro-once flags must survive within a session but reset on re-lock
 * (so the banner "returns next launch", W0 07), and no manager may create a wrap from a
 * secret that normalizes to empty (W1 §1 advisory).
 */
class RecoveryStateTest {
    @Test
    fun `re-locking the session resets both nag flags`() {
        RecoveryPromptState.bannerDismissedThisSession = true
        RecoveryPromptState.introOfferedThisSession = true

        VaultSession.clear() // what a re-lock does

        assertThat(RecoveryPromptState.bannerDismissedThisSession).isFalse()
        assertThat(RecoveryPromptState.introOfferedThisSession).isFalse()
    }

    @Test
    fun `in-memory manager reports configured only after setUp`() =
        runTest {
            val manager = InMemoryRecoveryManager()
            assertThat(manager.isConfigured()).isFalse()
            assertThat(manager.configuredQuestion()).isNull()

            manager.setUp(question = "First pet?", securityAnswer = "Rex", recoveryCode = "7K9F-2XQP-4MRT-8WVN")

            assertThat(manager.isConfigured()).isTrue()
            assertThat(manager.configuredQuestion()).isEqualTo("First pet?")
        }

    @Test
    fun `setUp rejects a secret that normalizes to empty`() =
        runTest {
            val manager = InMemoryRecoveryManager()
            runCatching { manager.setUp("Q", securityAnswer = "   ", recoveryCode = "7K9F-2XQP") }
                .let { assertThat(it.isFailure).isTrue() }
            runCatching { manager.setUp("Q", securityAnswer = "Rex", recoveryCode = "----") }
                .let { assertThat(it.isFailure).isTrue() }
            assertThat(manager.isConfigured()).isFalse()
        }
}
