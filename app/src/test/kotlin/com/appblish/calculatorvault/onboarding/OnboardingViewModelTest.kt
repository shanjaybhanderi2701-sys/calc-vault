package com.appblish.calculatorvault.onboarding

import com.appblish.calculatorvault.auth.InMemoryCredentialStore
import com.appblish.calculatorvault.auth.VaultKind
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

/**
 * Locks in the xlock-parity onboarding shape (APP-212): a clean calculator/PIN wizard with
 * no upfront permission wall and no security-question step. The recovery question is deferred
 * to first vault use (see DeferredRecoveryPrompt), so it must NOT appear in the wizard and
 * the real PIN must persist without any recovery being captured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `wizard has no permission wall or security-question step`() {
        // The whole ordered wizard, in order: language, the two PIN steps, two intro cards.
        assertThat(OnboardingStep.entries)
            .containsExactly(
                OnboardingStep.LANGUAGE,
                OnboardingStep.CREATE_PIN,
                OnboardingStep.CONFIRM_PIN,
                OnboardingStep.INTRO_PRIVATE,
                OnboardingStep.INTRO_ICONS,
            ).inOrder()
    }

    @Test
    fun `language advances straight to PIN creation with no file-access wall`() {
        val vm = OnboardingViewModel(InMemoryCredentialStore())
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.LANGUAGE)
        vm.onLanguageDone()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.CREATE_PIN)
    }

    @Test
    fun `confirming the PIN persists it and skips straight to the intro cards`() =
        runTest(dispatcher) {
            val store = InMemoryCredentialStore()
            val vm = OnboardingViewModel(store)
            vm.onLanguageDone()
            vm.onPinCreated("1234")
            vm.onPinConfirmed("1234")
            dispatcher.scheduler.advanceUntilIdle()

            // Straight to the intro cards — no security-question step in between.
            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.INTRO_PRIVATE)
            // The real PIN is set, but recovery is deferred: nothing captured during onboarding.
            assertThat(store.resolve("1234")).isEqualTo(VaultKind.Real)
            assertThat(store.recoveryInfo()).isNull()
        }

    @Test
    fun `mismatched confirmation bounces back to create without setting a pin`() =
        runTest(dispatcher) {
            val store = InMemoryCredentialStore()
            val vm = OnboardingViewModel(store)
            vm.onLanguageDone()
            vm.onPinCreated("1234")
            vm.onPinConfirmed("9999")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.CREATE_PIN)
            assertThat(vm.state.value.mismatch).isTrue()
            assertThat(store.resolve("1234")).isNull()
        }
}
