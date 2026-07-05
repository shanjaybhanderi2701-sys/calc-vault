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
 * Locks in the Phase-1 onboarding shape (build spec §1): a clean calculator/PIN wizard with
 * no upfront permission wall and no recovery step of any kind — per build spec §0, PIN
 * recovery does not exist anywhere in Phase 1 (deferred to a later phase, accepted risk).
 * The real PIN must persist without any recovery being captured, and the language step must
 * show the S3 "Setting Up Language" loader before advancing while persisting the choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `wizard has no permission wall or recovery step`() {
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
    fun `language Done shows the applying loader then advances to PIN creation`() =
        runTest(dispatcher) {
            val vm = OnboardingViewModel(InMemoryCredentialStore(), saveLanguage = {})
            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.LANGUAGE)

            vm.onLanguageDone()

            // Loader is up immediately; the wizard has not advanced yet.
            assertThat(vm.state.value.applyingLanguage).isTrue()
            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.LANGUAGE)

            // Just shy of the minimum dwell the loader is still showing…
            dispatcher.scheduler.advanceTimeBy(LANGUAGE_APPLY_MILLIS - 1)
            dispatcher.scheduler.runCurrent()
            assertThat(vm.state.value.applyingLanguage).isTrue()

            // …and once the dwell elapses it advances straight to PIN creation.
            dispatcher.scheduler.advanceUntilIdle()
            assertThat(vm.state.value.applyingLanguage).isFalse()
            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.CREATE_PIN)
        }

    @Test
    fun `language Done persists the chosen language fire-and-forget`() =
        runTest(dispatcher) {
            var saved: String? = null
            val vm = OnboardingViewModel(InMemoryCredentialStore(), saveLanguage = { saved = it })
            vm.selectLanguage("Hindi")
            vm.onLanguageDone()
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(saved).isEqualTo("Hindi")
        }

    @Test
    fun `a failing language save never stalls the loader or the wizard`() =
        runTest(dispatcher) {
            val vm = OnboardingViewModel(InMemoryCredentialStore(), saveLanguage = { error("store unavailable") })
            vm.onLanguageDone()
            dispatcher.scheduler.advanceUntilIdle()

            // Persistence is best-effort; the wizard still advances after the dwell.
            assertThat(vm.state.value.applyingLanguage).isFalse()
            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.CREATE_PIN)
        }

    @Test
    fun `back during the loader is a no-op and never gets stuck`() =
        runTest(dispatcher) {
            val vm = OnboardingViewModel(InMemoryCredentialStore(), saveLanguage = {})
            vm.onLanguageDone()
            assertThat(vm.state.value.applyingLanguage).isTrue()

            // Back while the loader shows: language is the first step, so nothing changes…
            vm.onBack()
            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.LANGUAGE)

            // …and the loader still resolves forward on its own — no stuck state.
            dispatcher.scheduler.advanceUntilIdle()
            assertThat(vm.state.value.applyingLanguage).isFalse()
            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.CREATE_PIN)
        }

    @Test
    fun `confirming the PIN persists it and skips straight to the intro cards`() =
        runTest(dispatcher) {
            val store = InMemoryCredentialStore()
            val vm = OnboardingViewModel(store, saveLanguage = {})
            vm.onLanguageDone()
            dispatcher.scheduler.advanceUntilIdle()
            vm.onPinCreated("1234")
            vm.onPinConfirmed("1234")
            dispatcher.scheduler.advanceUntilIdle()

            // Straight to the intro cards — no recovery step in between (none exists in Phase 1).
            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.INTRO_PRIVATE)
            // The real PIN is set and nothing recovery-related is captured during onboarding.
            assertThat(store.resolve("1234")).isEqualTo(VaultKind.Real)
            assertThat(store.recoveryInfo()).isNull()
        }

    @Test
    fun `mismatched confirmation bounces back to create without setting a pin`() =
        runTest(dispatcher) {
            val store = InMemoryCredentialStore()
            val vm = OnboardingViewModel(store, saveLanguage = {})
            vm.onLanguageDone()
            dispatcher.scheduler.advanceUntilIdle()
            vm.onPinCreated("1234")
            vm.onPinConfirmed("9999")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(vm.state.value.step).isEqualTo(OnboardingStep.CREATE_PIN)
            assertThat(vm.state.value.mismatch).isTrue()
            assertThat(store.resolve("1234")).isNull()
        }
}
