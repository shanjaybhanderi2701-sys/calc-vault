package com.appblish.calculatorvault.recovery

import com.appblish.calculatorvault.auth.CredentialStore
import com.appblish.calculatorvault.auth.InMemoryCredentialStore
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.crypto.RecoveryReKeyer
import com.appblish.calculatorvault.vault.crypto.RecoveryResetOutcome
import com.appblish.calculatorvault.vault.crypto.RecoveryVerifyOutcome
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
 * Locks in the APP-325 W3 recovery reset behaviour at the ViewModel seam:
 *
 *  - the credential ([CredentialStore.setRealPin]) commits **only after** the envelope re-wrap
 *    succeeds (the same commit-ordering invariant the change-PIN flow enforces, APP-245),
 *  - wrong recovery secrets accumulate an escalating backoff (spec §1.6), and while locked out
 *    the flow won't even attempt an unwrap,
 *  - an unconfigured vault lands on the honest "unrecoverable" dead-end (spec §1.5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryUnlockViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = mutableListOf<String>()

    /** Fake reset seam: a fixed [correctSecret] decides CORRECT/WRONG; records the commit order. */
    private inner class FakeReKeyer(
        private val correctSecret: String?,
        private val configured: Boolean = true,
    ) : RecoveryReKeyer {
        override suspend fun verify(
            method: RecoveryMethod,
            secret: String,
        ): RecoveryVerifyOutcome =
            when {
                !configured -> RecoveryVerifyOutcome.NOT_CONFIGURED
                secret == correctSecret -> RecoveryVerifyOutcome.CORRECT
                else -> RecoveryVerifyOutcome.WRONG_SECRET
            }

        override suspend fun resetPin(
            method: RecoveryMethod,
            secret: String,
            newPin: String,
        ): RecoveryResetOutcome {
            events += "resetPin"
            return when {
                !configured -> RecoveryResetOutcome.NOT_CONFIGURED
                secret == correctSecret -> RecoveryResetOutcome.RESET
                else -> RecoveryResetOutcome.WRONG_SECRET
            }
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

    private var clock = 1_000L

    private fun vm(
        reKeyer: RecoveryReKeyer,
        store: CredentialStore,
        attemptStore: RecoveryAttemptStore = InMemoryRecoveryAttemptStore(),
        configured: Boolean = true,
    ) = RecoveryUnlockViewModel(
        method = RecoveryMethod.RECOVERY_CODE,
        reKeyer = reKeyer,
        recoveryManager = InMemoryRecoveryManager(configured = configured),
        credentialStore = store,
        attemptStore = attemptStore,
        now = { clock },
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        Dispatchers.resetMain()
        VaultSession.clear()
    }

    @Test
    fun `happy path re-wraps the envelope before committing the credential`() =
        runTest(dispatcher) {
            val store = RecordingStore()
            events.clear()
            val viewModel = vm(FakeReKeyer(correctSecret = CODE), store)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onSubmitSecret(CODE)
            dispatcher.scheduler.advanceUntilIdle()
            assertThat(viewModel.state.value.stage).isEqualTo(RecoveryUnlockStage.NEW_PIN)

            viewModel.onNewPin("5678")
            viewModel.onConfirmPin("5678")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.state.value.stage).isEqualTo(RecoveryUnlockStage.DONE)
            // The invariant: envelope re-wrap first, credential commit second.
            assertThat(events).containsExactly("resetPin", "setRealPin").inOrder()
            assertThat(store.resolve("5678")).isEqualTo(VaultKind.Real)
            // The live session now holds the new PIN so the vault is immediately usable.
            assertThat(VaultSession.passphrase).isEqualTo("5678")
        }

    @Test
    fun `confirm mismatch bounces back to the new-pin step`() =
        runTest(dispatcher) {
            val viewModel = vm(FakeReKeyer(correctSecret = CODE), RecordingStore())
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.onSubmitSecret(CODE)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onNewPin("5678")
            viewModel.onConfirmPin("9999")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.state.value.stage).isEqualTo(RecoveryUnlockStage.NEW_PIN)
            assertThat(viewModel.state.value.error).contains("didn't match")
        }

    @Test
    fun `wrong secret records backoff and eventually locks out`() =
        runTest(dispatcher) {
            val attempts = InMemoryRecoveryAttemptStore()
            val viewModel = vm(FakeReKeyer(correctSecret = CODE), RecordingStore(), attemptStore = attempts)
            dispatcher.scheduler.advanceUntilIdle()

            // Two wrong tries are the fat-finger grace — still no lockout.
            repeat(2) {
                viewModel.onSubmitSecret("WRONG")
                dispatcher.scheduler.advanceUntilIdle()
            }
            assertThat(viewModel.state.value.lockedOut).isFalse()
            assertThat(viewModel.state.value.error).isNotNull()

            // The third wrong try trips the 30s lockout.
            viewModel.onSubmitSecret("WRONG")
            dispatcher.scheduler.advanceUntilIdle()
            assertThat(viewModel.state.value.lockedOut).isTrue()
            assertThat(attempts.failedAttempts(RecoveryMethod.RECOVERY_CODE)).isEqualTo(3)
        }

    @Test
    fun `locked out flow does not attempt an unwrap`() =
        runTest(dispatcher) {
            val attempts = InMemoryRecoveryAttemptStore()
            // Seed 3 failures at the current clock so we are inside the 30s window.
            repeat(3) { attempts.recordFailure(RecoveryMethod.RECOVERY_CODE, clock) }
            events.clear()
            val reKeyer =
                object : RecoveryReKeyer {
                    override suspend fun verify(
                        method: RecoveryMethod,
                        secret: String,
                    ): RecoveryVerifyOutcome {
                        events += "verify"
                        return RecoveryVerifyOutcome.CORRECT
                    }

                    override suspend fun resetPin(
                        method: RecoveryMethod,
                        secret: String,
                        newPin: String,
                    ) = RecoveryResetOutcome.RESET
                }
            val viewModel = vm(reKeyer, RecordingStore(), attemptStore = attempts)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onSubmitSecret(CODE)
            dispatcher.scheduler.advanceUntilIdle()

            // Even a correct secret is refused while locked out — no crypto work is attempted.
            assertThat(viewModel.state.value.stage).isEqualTo(RecoveryUnlockStage.ENTER_SECRET)
            assertThat(viewModel.state.value.lockedOut).isTrue()
            assertThat(events).doesNotContain("verify")
        }

    @Test
    fun `unconfigured vault shows the unrecoverable dead-end`() =
        runTest(dispatcher) {
            val viewModel =
                vm(FakeReKeyer(correctSecret = null, configured = false), RecordingStore(), configured = false)
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.state.value.stage).isEqualTo(RecoveryUnlockStage.UNRECOVERABLE)
        }

    @Test
    fun `a seam that throws clears the spinner instead of hanging it (APP-331 O1)`() =
        runTest(dispatcher) {
            // Simulate storage lost / a malformed key file mid-verify: the seam throws instead of
            // returning an outcome. The launch must catch it, surface the honest error and release
            // busy — never cancel the coroutine and strand busy=true (a permanently spinning verify).
            val throwingReKeyer =
                object : RecoveryReKeyer {
                    override suspend fun verify(
                        method: RecoveryMethod,
                        secret: String,
                    ): RecoveryVerifyOutcome = throw java.io.IOException("storage lost mid-verify")

                    override suspend fun resetPin(
                        method: RecoveryMethod,
                        secret: String,
                        newPin: String,
                    ) = RecoveryResetOutcome.RESET
                }
            val viewModel = vm(throwingReKeyer, RecordingStore())
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onSubmitSecret(CODE)
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.state.value.busy).isFalse()
            assertThat(viewModel.state.value.error).isNotNull()
            // Stays on the entry step — no phantom advance to NEW_PIN on an unproven identity.
            assertThat(viewModel.state.value.stage).isEqualTo(RecoveryUnlockStage.ENTER_SECRET)
        }

    @Test
    fun `a backoff write failure on a wrong secret clears the spinner (APP-331 O1 O2)`() =
        runTest(dispatcher) {
            // The atomic attempt store now throws when it cannot persist (APP-331 O2). A wrong
            // secret triggers recordFailure; if that throw escaped it would strand busy=true. The
            // launch's catch keeps the flow responsive.
            val throwingStore =
                object : RecoveryAttemptStore {
                    override fun failedAttempts(method: RecoveryMethod): Int = 0

                    override fun lastFailureAtMillis(method: RecoveryMethod): Long = 0L

                    override fun recordFailure(
                        method: RecoveryMethod,
                        nowMillis: Long,
                    ): Unit = throw java.io.IOException("could not persist attempt")

                    override fun clear(method: RecoveryMethod) = Unit
                }
            val viewModel = vm(FakeReKeyer(correctSecret = CODE), RecordingStore(), attemptStore = throwingStore)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onSubmitSecret("WRONG")
            dispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.state.value.busy).isFalse()
            assertThat(viewModel.state.value.error).isNotNull()
        }

    @Test
    fun `a transient credential write failure after RESET is retried so the reset still completes (APP-333)`() =
        runTest(dispatcher) {
            // resetPin already RESET the envelope to the new PIN; setRealPin throws once (a keystore
            // hiccup) then succeeds. The idempotent retry must land the credential so the vault ends
            // fully reset — a transient write can never strand it (envelope + credential both at new PIN).
            val delegate = InMemoryCredentialStore()
            val flakyStore =
                object : CredentialStore by delegate {
                    var attempts = 0

                    override suspend fun setRealPin(pin: String) {
                        attempts++
                        if (attempts == 1) throw java.io.IOException("transient keystore hiccup")
                        delegate.setRealPin(pin)
                    }
                }
            val viewModel = vm(FakeReKeyer(correctSecret = CODE), flakyStore)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onSubmitSecret(CODE)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.onNewPin("5678")
            viewModel.onConfirmPin("5678")
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertThat(state.stage).isEqualTo(RecoveryUnlockStage.DONE)
            assertThat(state.busy).isFalse()
            // Credential caught up to the moved envelope: the new PIN authenticates and the session holds it.
            assertThat(delegate.resolve("5678")).isEqualTo(VaultKind.Real)
            assertThat(VaultSession.passphrase).isEqualTo("5678")
        }

    @Test
    fun `a persistent credential write failure after RESET reports the honest diverged state (APP-333)`() =
        runTest(dispatcher) {
            // resetPin already RESET the envelope to the new PIN; setRealPin then fails on every retry.
            // The flow must (a) not hang the spinner, (b) NOT claim the vault is unchanged — the
            // envelope really moved — and (c) route back to a covered path (re-verify → re-run the
            // reset against the already-moved envelope) rather than dead-end.
            val throwingStore =
                object : CredentialStore by InMemoryCredentialStore() {
                    override suspend fun setRealPin(pin: String): Unit = throw java.io.IOException("write failed")
                }
            val viewModel = vm(FakeReKeyer(correctSecret = CODE), throwingStore)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onSubmitSecret(CODE)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.onNewPin("5678")
            viewModel.onConfirmPin("5678")
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            // (a) no stuck spinner
            assertThat(state.busy).isFalse()
            // (b) honest error — the envelope DID move, so never claim it is unchanged
            assertThat(state.error).isNotNull()
            assertThat(state.error!!.lowercase()).doesNotContain("unchanged")
            assertThat(state.error).contains("changed")
            // (c) a covered path back to a consistent state: re-verify to re-run the reset
            assertThat(state.stage).isEqualTo(RecoveryUnlockStage.ENTER_SECRET)
        }

    private companion object {
        const val CODE = "7K9F2XQP4MRT8WVN"
    }
}
