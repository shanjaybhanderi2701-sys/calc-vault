package com.appblish.calculatorvault.recovery

import com.appblish.calculatorvault.vault.crypto.RecoverySecrets
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * PIN Recovery W2 (APP-324) setup flow — drives [RecoverySetupViewModel] against a real
 * temp-file [VaultKeyFile] (pure JVM, matching the W1 envelope tests) so the DoD "Wrap B/C
 * creation" is proven end-to-end from the flow: completing setup must add Wrap B (answer)
 * and Wrap C (code) that both unwrap the SAME immutable DEK the PIN already unwraps (spec
 * §1), and a wrong confirmation must never write anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoverySetupViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A [RecoveryManager] over a real temp key file — no Android context needed. */
    private class FileRecoveryManager(
        val keyFile: VaultKeyFile,
        private val pin: String,
    ) : RecoveryManager {
        var question: String? = null

        override suspend fun isConfigured(): Boolean = keyFile.exists() && keyFile.isRecoveryConfigured()

        override suspend fun configuredQuestion(): String? = question

        override suspend fun setUp(
            question: String,
            securityAnswer: String,
            recoveryCode: String,
        ) {
            require(question.isNotBlank())
            require(RecoverySecrets.normalizeAnswer(securityAnswer).isNotEmpty())
            require(RecoverySecrets.normalizeRecoveryCode(recoveryCode).isNotEmpty())
            keyFile.setUpRecovery(pin, securityAnswer, recoveryCode)
            this.question = question
        }
    }

    private fun freshVault(pin: String = "1234"): Pair<VaultKeyFile, FileRecoveryManager> {
        val file = File.createTempFile("vaultkey", ".txt").also { it.delete() }
        val keyFile = VaultKeyFile(file)
        keyFile.unlockOrCreate(pin) // create the vault (Wrap A only)
        return keyFile to FileRecoveryManager(keyFile, pin)
    }

    @Test
    fun `completing the flow writes Wrap B and Wrap C for the same DEK`() =
        runTest {
            val (keyFile, manager) = freshVault(pin = "1234")
            val dek = keyFile.unlock("1234").encoded
            val vm = RecoverySetupViewModel(recoveryManager = manager)

            vm.setAnswer("  Fluffy The Cat ")
            assertThat(vm.uiState.value.canLeaveQuestion).isTrue()
            vm.leaveQuestion()
            assertThat(vm.uiState.value.step).isEqualTo(RecoverySetupStep.CODE)

            val code = vm.uiState.value.recoveryCode
            vm.setCodeSaved(true)
            vm.leaveCode()
            assertThat(vm.uiState.value.step).isEqualTo(RecoverySetupStep.CONFIRM)

            // Re-enter the code in a different shape — normalization must still match.
            vm.setConfirmInput(code.lowercase().replace("-", ""))
            vm.confirmAndSave()

            assertThat(vm.uiState.value.step).isEqualTo(RecoverySetupStep.HINT)
            assertThat(keyFile.isRecoveryConfigured()).isTrue()
            // Both new wraps unwrap the SAME DEK (case/space-insensitive answer, any code shape).
            assertThat(keyFile.unlockWithAnswer("fluffy the cat").encoded).isEqualTo(dek)
            assertThat(keyFile.unlockWithRecoveryCode(code).encoded).isEqualTo(dek)
        }

    @Test
    fun `a mismatched confirmation errors and writes nothing`() =
        runTest {
            val (keyFile, manager) = freshVault()
            val vm = RecoverySetupViewModel(recoveryManager = manager)
            vm.setAnswer("Rex")
            vm.leaveQuestion()
            vm.setCodeSaved(true)
            vm.leaveCode()

            vm.setConfirmInput("ZZZZ-ZZZZ-ZZZZ-ZZZZ")
            vm.confirmAndSave()

            assertThat(vm.uiState.value.confirmError).isTrue()
            assertThat(vm.uiState.value.step).isEqualTo(RecoverySetupStep.CONFIRM)
            assertThat(keyFile.isRecoveryConfigured()).isFalse()
        }

    @Test
    fun `an empty-normalized answer keeps Continue disabled`() =
        runTest {
            val (_, manager) = freshVault()
            val vm = RecoverySetupViewModel(recoveryManager = manager)
            vm.setAnswer("   ")
            assertThat(vm.uiState.value.canLeaveQuestion).isFalse()
            vm.leaveQuestion()
            assertThat(vm.uiState.value.step).isEqualTo(RecoverySetupStep.QUESTION)
        }

    @Test
    fun `a custom question must be non-blank to continue`() =
        runTest {
            val (_, manager) = freshVault()
            val vm = RecoverySetupViewModel(recoveryManager = manager)
            vm.useCustomQuestion()
            vm.setAnswer("Rex")
            assertThat(vm.uiState.value.canLeaveQuestion).isFalse()
            vm.setCustomQuestion("Street I grew up on?")
            assertThat(vm.uiState.value.canLeaveQuestion).isTrue()
        }

    @Test
    fun `recovery code input formatter groups and strips`() {
        assertThat(formatRecoveryCodeInput("7k9f2xqp4mrt8wvn")).isEqualTo("7K9F-2XQP-4MRT-8WVN")
        assertThat(formatRecoveryCodeInput("7K9F 2XQP")).isEqualTo("7K9F-2XQP")
        // Never exceeds the 16-char envelope length regardless of extra characters typed.
        assertThat(formatRecoveryCodeInput("AAAA-BBBB-CCCC-DDDD-EEEE")).isEqualTo("AAAA-BBBB-CCCC-DDDD")
    }
}
