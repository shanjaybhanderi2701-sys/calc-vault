package com.appblish.calculatorvault.settings

import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.auth.InMemoryCredentialStore
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.crypto.ReKeyOutcome
import com.appblish.calculatorvault.vault.crypto.VaultReKeyer
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
 * Locks in the APP-245 data-safety invariant for Settings → Change PIN: the credential
 * commit ([CredentialStore.setRealPin]) happens **only after** the `.vaultkey` envelope has
 * followed the new PIN ([VaultReKeyer]). Any other order — or committing when the envelope
 * couldn't move — recreates the stranded-vault P0 (new PIN passes auth, GCM unwrap fails,
 * vault renders empty and is unrecoverable once the old PIN is forgotten).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangePinViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** Records the call order across the seam so commit-after-rewrap is provable. */
    private val events = mutableListOf<String>()

    private inner class RecordingReKeyer(
        var outcome: ReKeyOutcome,
    ) : VaultReKeyer {
        var oldSeen: String? = null
        var newSeen: String? = null

        override suspend fun rewrap(
            oldPassphrase: String,
            newPassphrase: String,
        ): ReKeyOutcome {
            oldSeen = oldPassphrase
            newSeen = newPassphrase
            events += "rewrap"
            return outcome
        }
    }

    private inner class RecordingStore(
        private val delegate: InMemoryCredentialStore = InMemoryCredentialStore(),
    ) : CredentialStore by delegate {
        override suspend fun setRealPin(pin: String) {
            events += "setRealPin"
            delegate.setRealPin(pin)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        Dispatchers.resetMain()
        VaultSession.clear()
    }

    private suspend fun store(pin: String = "1111"): RecordingStore = RecordingStore().also { it.setRealPin(pin) }

    @Test
    fun `happy path rewraps the envelope before committing the credential`() =
        runTest(dispatcher) {
            val store = store()
            events.clear()
            val reKeyer = RecordingReKeyer(ReKeyOutcome.REWRAPPED)
            val vm = ChangePinViewModel(store, reKeyer)

            vm.onVerify("1111")
            dispatcher.scheduler.advanceUntilIdle()
            vm.onNew("2222")
            vm.onConfirm("2222")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(vm.state.value.stage).isEqualTo(ChangePinStage.DONE)
            // The invariant itself: envelope first, credential second.
            assertThat(events).containsExactly("rewrap", "setRealPin").inOrder()
            assertThat(reKeyer.oldSeen).isEqualTo("1111")
            assertThat(reKeyer.newSeen).isEqualTo("2222")
            assertThat(store.resolve("2222")).isEqualTo(VaultKind.Real)
            assertThat(store.resolve("1111")).isNull()
        }

    @Test
    fun `no key file yet still allows the change`() =
        runTest(dispatcher) {
            val store = store()
            val vm = ChangePinViewModel(store, RecordingReKeyer(ReKeyOutcome.NO_KEY_FILE))

            vm.onVerify("1111")
            dispatcher.scheduler.advanceUntilIdle()
            vm.onNew("2222")
            vm.onConfirm("2222")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(vm.state.value.stage).isEqualTo(ChangePinStage.DONE)
            assertThat(store.resolve("2222")).isEqualTo(VaultKind.Real)
        }

    @Test
    fun `storage unavailable blocks the change and keeps the old pin`() =
        runTest(dispatcher) {
            val store = store()
            events.clear()
            val vm = ChangePinViewModel(store, RecordingReKeyer(ReKeyOutcome.STORAGE_UNAVAILABLE))

            vm.onVerify("1111")
            dispatcher.scheduler.advanceUntilIdle()
            vm.onNew("2222")
            vm.onConfirm("2222")
            dispatcher.scheduler.advanceUntilIdle()

            // Refused with a clear message; the credential never moved.
            assertThat(vm.state.value.stage).isEqualTo(ChangePinStage.NEW)
            assertThat(vm.state.value.error).contains("All files access")
            assertThat(events).containsExactly("rewrap")
            assertThat(store.resolve("1111")).isEqualTo(VaultKind.Real)
            assertThat(store.resolve("2222")).isNull()
        }

    @Test
    fun `failed rewrap blocks the change and keeps the old pin`() =
        runTest(dispatcher) {
            val store = store()
            events.clear()
            val vm = ChangePinViewModel(store, RecordingReKeyer(ReKeyOutcome.FAILED))

            vm.onVerify("1111")
            dispatcher.scheduler.advanceUntilIdle()
            vm.onNew("2222")
            vm.onConfirm("2222")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(vm.state.value.stage).isEqualTo(ChangePinStage.NEW)
            assertThat(vm.state.value.error).isNotNull()
            assertThat(events).containsExactly("rewrap")
            assertThat(store.resolve("1111")).isEqualTo(VaultKind.Real)
            assertThat(store.resolve("2222")).isNull()
        }

    @Test
    fun `a live real-vault session follows the new pin`() =
        runTest(dispatcher) {
            val store = store()
            VaultSession.begin("1111")
            val vm = ChangePinViewModel(store, RecordingReKeyer(ReKeyOutcome.REWRAPPED))

            vm.onVerify("1111")
            dispatcher.scheduler.advanceUntilIdle()
            vm.onNew("2222")
            vm.onConfirm("2222")
            dispatcher.scheduler.advanceUntilIdle()

            // Otherwise the very next unlock() this session would derive the retired KEK
            // against the freshly re-wrapped key file and render the vault empty again.
            assertThat(VaultSession.passphrase).isEqualTo("2222")
            assertThat(VaultSession.namespace).isEmpty()
        }

    @Test
    fun `a decoy session is left untouched by a real pin change`() =
        runTest(dispatcher) {
            val store = store()
            VaultSession.begin("9876", namespace = "decoy_0")
            val vm = ChangePinViewModel(store, RecordingReKeyer(ReKeyOutcome.REWRAPPED))

            vm.onVerify("1111")
            dispatcher.scheduler.advanceUntilIdle()
            vm.onNew("2222")
            vm.onConfirm("2222")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(VaultSession.passphrase).isEqualTo("9876")
            assertThat(VaultSession.namespace).isEqualTo("decoy_0")
        }
}
