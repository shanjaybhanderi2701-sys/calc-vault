package com.appblish.calculatorvault.settings

import com.appblish.calculatorvault.auth.InMemoryCredentialStore
import com.appblish.calculatorvault.auth.RecoverySetup
import com.appblish.calculatorvault.auth.SecurityQuestion
import com.appblish.calculatorvault.vault.crypto.RecoveryReWrapper
import com.appblish.calculatorvault.vault.crypto.RecoverySecrets
import com.appblish.calculatorvault.vault.crypto.RecoveryUpdateOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * Settings → PIN Recovery (PIN Recovery W4) management flows. Proves the regenerate-code path
 * re-wraps Wrap C **only after** the user confirms the new code, and the change-question path
 * re-wraps Wrap B and keeps the credential store's question in step — both refusing to commit
 * on a re-wrap failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeReWrapper(
        var codeOutcome: RecoveryUpdateOutcome = RecoveryUpdateOutcome.UPDATED,
        var answerOutcome: RecoveryUpdateOutcome = RecoveryUpdateOutcome.UPDATED,
    ) : RecoveryReWrapper {
        var lastCode: String? = null
        var lastAnswer: String? = null
        var codeCalls = 0
        var answerCalls = 0

        override suspend fun replaceRecoveryCode(newCode: String): RecoveryUpdateOutcome {
            codeCalls++
            lastCode = newCode
            return codeOutcome
        }

        override suspend fun replaceSecurityAnswer(newAnswer: String): RecoveryUpdateOutcome {
            answerCalls++
            lastAnswer = newAnswer
            return answerOutcome
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun store(question: SecurityQuestion = SecurityQuestion.PET_NAME): InMemoryCredentialStore =
        InMemoryCredentialStore().also {
            it.setRealPin("1111")
            it.setRecovery(RecoverySetup(question = question, answer = "rex", recoveryEmail = "", hint = ""))
        }

    // A SecureRandom that yields deterministic bytes so the generated code is stable per run.
    private fun fixedRandom() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3, 4)) }

    private fun vm(
        store: InMemoryCredentialStore,
        reWrapper: RecoveryReWrapper,
    ) = PinRecoveryViewModel(store, reWrapper, fixedRandom())

    @Test
    fun `loads recovery status and the current question`() =
        runTest(dispatcher) {
            val vm = vm(store(), FakeReWrapper())
            dispatcher.scheduler.advanceUntilIdle()
            assertThat(vm.state.value.hasRecovery).isTrue()
            assertThat(vm.state.value.question).isEqualTo(SecurityQuestion.PET_NAME)
        }

    @Test
    fun `regenerate re-wraps Wrap C only after the code is confirmed`() =
        runTest(dispatcher) {
            val reWrapper = FakeReWrapper()
            val vm = vm(store(), reWrapper)
            dispatcher.scheduler.advanceUntilIdle()

            vm.startRegenerate()
            val code = vm.state.value.newCode
            assertThat(code).isNotEmpty()
            assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.REGEN_SHOW)

            // Continue is gated on the "I've saved it" checkbox.
            vm.regenerateContinue()
            assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.REGEN_SHOW)
            // Nothing re-wrapped yet — the old code is still valid.
            assertThat(reWrapper.codeCalls).isEqualTo(0)

            vm.setCodeSaved(true)
            vm.regenerateContinue()
            assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.REGEN_CONFIRM)

            // Re-enter with dashes stripped / lower-cased — normalization must still match.
            vm.setConfirmEntry(code.replace("-", "").lowercase())
            vm.confirmRegenerate()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(reWrapper.codeCalls).isEqualTo(1)
            assertThat(reWrapper.lastCode).isEqualTo(code)
            assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.REGEN_DONE)
        }

    @Test
    fun `a mismatched re-entry does not re-wrap`() =
        runTest(dispatcher) {
            val reWrapper = FakeReWrapper()
            val vm = vm(store(), reWrapper)
            dispatcher.scheduler.advanceUntilIdle()

            vm.startRegenerate()
            vm.setCodeSaved(true)
            vm.regenerateContinue()
            vm.setConfirmEntry("WRONG-CODE-HERE-XXXX")
            vm.confirmRegenerate()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(reWrapper.codeCalls).isEqualTo(0)
            assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.REGEN_CONFIRM)
            assertThat(vm.state.value.error).isNotNull()
        }

    @Test
    fun `a failed re-wrap keeps the old code and surfaces an error`() =
        runTest(dispatcher) {
            val reWrapper = FakeReWrapper(codeOutcome = RecoveryUpdateOutcome.STORAGE_UNAVAILABLE)
            val vm = vm(store(), reWrapper)
            dispatcher.scheduler.advanceUntilIdle()

            vm.startRegenerate()
            val code = vm.state.value.newCode
            vm.setCodeSaved(true)
            vm.regenerateContinue()
            vm.setConfirmEntry(code)
            vm.confirmRegenerate()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(reWrapper.codeCalls).isEqualTo(1)
            assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.REGEN_CONFIRM)
            assertThat(vm.state.value.error).isNotNull()
        }

    @Test
    fun `change question re-wraps Wrap B and updates the stored question`() =
        runTest(dispatcher) {
            val store = store(question = SecurityQuestion.PET_NAME)
            val reWrapper = FakeReWrapper()
            val vm = vm(store, reWrapper)
            dispatcher.scheduler.advanceUntilIdle()

            vm.startChangeQuestion()
            vm.setDraftQuestion(SecurityQuestion.BIRTH_CITY)
            vm.setDraftAnswer("  London  ")
            vm.confirmChangeQuestion()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(reWrapper.answerCalls).isEqualTo(1)
            assertThat(reWrapper.lastAnswer).isEqualTo("  London  ") // raw; VaultKeyFile normalizes internally
            assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.CHANGE_DONE)
            assertThat(vm.state.value.question).isEqualTo(SecurityQuestion.BIRTH_CITY)
            assertThat(store.recoveryInfo()?.question).isEqualTo(SecurityQuestion.BIRTH_CITY)
        }

    @Test
    fun `a blank answer is refused before any re-wrap`() =
        runTest(dispatcher) {
            val reWrapper = FakeReWrapper()
            val vm = vm(store(), reWrapper)
            dispatcher.scheduler.advanceUntilIdle()

            vm.startChangeQuestion()
            vm.setDraftAnswer("   ")
            vm.confirmChangeQuestion()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(reWrapper.answerCalls).isEqualTo(0)
            assertThat(vm.state.value.error).isNotNull()
        }

    @Test
    fun `a failed change re-wrap does not move the stored question`() =
        runTest(dispatcher) {
            val store = store(question = SecurityQuestion.PET_NAME)
            val reWrapper = FakeReWrapper(answerOutcome = RecoveryUpdateOutcome.FAILED)
            val vm = vm(store, reWrapper)
            dispatcher.scheduler.advanceUntilIdle()

            vm.startChangeQuestion()
            vm.setDraftQuestion(SecurityQuestion.BIRTH_CITY)
            vm.setDraftAnswer("London")
            vm.confirmChangeQuestion()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(vm.state.value.error).isNotNull()
            assertThat(store.recoveryInfo()?.question).isEqualTo(SecurityQuestion.PET_NAME)
        }

    @Test
    fun `generated code normalizes to a stable Wrap-C secret`() {
        // Sanity: the display form always normalizes to a non-empty alphanumeric secret.
        val code = RecoverySecrets.generateRecoveryCode(fixedRandom())
        assertThat(RecoverySecrets.normalizeRecoveryCode(code)).isNotEmpty()
    }
}
