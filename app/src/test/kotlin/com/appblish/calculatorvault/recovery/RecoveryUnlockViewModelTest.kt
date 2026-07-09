package com.appblish.calculatorvault.recovery

import com.appblish.calculatorvault.auth.InMemoryCredentialStore
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.crypto.RecoveryPinReset
import com.appblish.calculatorvault.vault.crypto.RecoveryResetOutcome
import com.appblish.calculatorvault.vault.crypto.RecoveryVerifyOutcome
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

/**
 * PIN Recovery W3 (APP-325) forgot-PIN flow — drives [RecoveryUnlockViewModel] against a
 * scriptable fake [RecoveryPinReset] and an [InMemoryCredentialStore]. It proves the flow
 * wiring the on-device crypto DoD test can't cheaply assert: a good verify advances to the
 * PIN steps and, on confirm, the auth credential is rotated ONLY after the seam re-wrapped
 * the envelope (spec §5.2 ordering); a wrong secret surfaces the backoff and stays put; a PIN
 * mismatch never commits; and an unavailable vault lands on the honest dead-end (no false
 * "contact support", spec §1.5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryUnlockViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A [RecoveryPinReset] whose outcomes the test scripts; records what it was asked to do. */
    private class FakePinReset(
        var verifyOutcome: RecoveryVerifyOutcome = RecoveryVerifyOutcome.Verified,
        var resetOutcome: RecoveryResetOutcome = RecoveryResetOutcome.RESET,
        var lockout: Long = 0L,
    ) : RecoveryPinReset {
        var verifiedSecret: String? = null
        var resetPinValue: String? = null

        override suspend fun lockoutRemainingMillis(method: RecoveryMethod): Long = lockout

        override suspend fun verify(
            method: RecoveryMethod,
            secret: String,
        ): RecoveryVerifyOutcome {
            verifiedSecret = secret
            return verifyOutcome
        }

        override suspend fun resetPin(newPin: String): RecoveryResetOutcome {
            resetPinValue = newPin
            return resetOutcome
        }
    }

    private fun vm(
        method: RecoveryMethod = RecoveryMethod.SECURITY_ANSWER,
        pinReset: FakePinReset = FakePinReset(),
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
    ) = RecoveryUnlockViewModel(
        method = method,
        pinReset = pinReset,
        recoveryManager = InMemoryRecoveryManager(configured = true),
        store = store,
    )

    @Test
    fun `good verify then matching new pin resets the credential in order`() =
        runTest {
            val pinReset = FakePinReset(verifyOutcome = RecoveryVerifyOutcome.Verified)
            val store = InMemoryCredentialStore().apply { setRealPin("1234") }
            val model = vm(pinReset = pinReset, store = store)

            model.onSecretChanged("fluffy the cat")
            model.onSubmitSecret()
            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.NEW_PIN)
            assertThat(pinReset.verifiedSecret).isEqualTo("fluffy the cat")

            model.onNewPin("9999")
            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.CONFIRM_PIN)

            model.onConfirmPin("9999")
            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.DONE)
            // The envelope moved first (seam saw the new PIN) and the credential rotated to it.
            assertThat(pinReset.resetPinValue).isEqualTo("9999")
            assertThat(store.resolve("9999")).isEqualTo(VaultKind.Real)
            assertThat(store.resolve("1234")).isNull()
        }

    @Test
    fun `a failed re-wrap does not rotate the credential`() =
        runTest {
            val pinReset = FakePinReset(resetOutcome = RecoveryResetOutcome.FAILED)
            val store = InMemoryCredentialStore().apply { setRealPin("1234") }
            val model = vm(pinReset = pinReset, store = store)

            model.onSecretChanged("fluffy the cat")
            model.onSubmitSecret()
            model.onNewPin("9999")
            model.onConfirmPin("9999")

            // Back on the new-PIN step with an error; the old PIN still stands.
            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.NEW_PIN)
            assertThat(model.state.value.error).isNotNull()
            assertThat(store.resolve("1234")).isEqualTo(VaultKind.Real)
            assertThat(store.resolve("9999")).isNull()
        }

    @Test
    fun `mismatched confirmation never commits and returns to the new-pin step`() =
        runTest {
            val pinReset = FakePinReset()
            val store = InMemoryCredentialStore().apply { setRealPin("1234") }
            val model = vm(pinReset = pinReset, store = store)

            model.onSecretChanged("fluffy the cat")
            model.onSubmitSecret()
            model.onNewPin("9999")
            model.onConfirmPin("8888")

            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.NEW_PIN)
            assertThat(model.state.value.error).contains("didn't match")
            assertThat(pinReset.resetPinValue).isNull()
            assertThat(store.resolve("1234")).isEqualTo(VaultKind.Real)
        }

    @Test
    fun `wrong secret surfaces the backoff and stays on verify`() =
        runTest {
            val pinReset = FakePinReset(verifyOutcome = RecoveryVerifyOutcome.WrongSecret(30_000L))
            val model = vm(pinReset = pinReset)

            model.onSecretChanged("nope")
            model.onSubmitSecret()

            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.VERIFY)
            assertThat(model.state.value.lockoutRemainingMillis).isEqualTo(30_000L)
            assertThat(model.state.value.error).contains("wait")
            assertThat(model.state.value.secretInput).isEmpty()
        }

    @Test
    fun `an unavailable vault lands on the honest dead-end`() =
        runTest {
            val pinReset = FakePinReset(verifyOutcome = RecoveryVerifyOutcome.Unavailable)
            val model = vm(pinReset = pinReset)

            model.onSecretChanged("whatever")
            model.onSubmitSecret()

            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.UNAVAILABLE)
        }

    @Test
    fun `a seam exception lands on the dead-end and clears the spinner`() =
        runTest {
            // Simulate All-Files-Access revoked mid-flow: the seam throws instead of returning.
            // The verify must not cancel the coroutine and strand verifying=true (APP-331 O1).
            val pinReset =
                object : RecoveryPinReset {
                    override suspend fun lockoutRemainingMillis(method: RecoveryMethod): Long = 0L

                    override suspend fun verify(
                        method: RecoveryMethod,
                        secret: String,
                    ): RecoveryVerifyOutcome = throw java.io.IOException("storage lost mid-verify")

                    override suspend fun resetPin(newPin: String): RecoveryResetOutcome =
                        RecoveryResetOutcome.NOT_VERIFIED
                }
            val model =
                RecoveryUnlockViewModel(
                    method = RecoveryMethod.SECURITY_ANSWER,
                    pinReset = pinReset,
                    recoveryManager = InMemoryRecoveryManager(configured = true),
                    store = InMemoryCredentialStore(),
                )

            model.onSecretChanged("whatever")
            model.onSubmitSecret()

            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.UNAVAILABLE)
            assertThat(model.state.value.verifying).isFalse()
        }

    @Test
    fun `a blank secret is refused without calling the seam`() =
        runTest {
            val pinReset = FakePinReset()
            val model = vm(pinReset = pinReset)

            model.onSecretChanged("   ")
            model.onSubmitSecret()

            assertThat(model.state.value.stage).isEqualTo(RecoveryUnlockStage.VERIFY)
            assertThat(model.state.value.error).isNotNull()
            assertThat(pinReset.verifiedSecret).isNull()
        }
}
