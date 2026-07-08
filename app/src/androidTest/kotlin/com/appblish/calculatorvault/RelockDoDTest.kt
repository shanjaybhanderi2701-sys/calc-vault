package com.appblish.calculatorvault

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.vault.DoDTestSupport
import com.appblish.calculatorvault.vault.EncryptedVaultContentRepository
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.VaultSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Board-added Phase-1 check #3 (APP-237/APP-241) — the original real-device failure: the
 * app must **re-lock on return from background**. Both leave-the-vault paths are driven
 * end-to-end against the real [MainActivity] spine — a genuine PIN unlock typed on the
 * calculator disguise, then:
 *
 *  1. **Backgrounding** (home press / Recents-return are the same whole-app ON_STOP
 *     signal `ProcessLifecycleOwner` reports): the in-memory session must be forgotten
 *     while still backgrounded, and the resumed UI must show the calculator disguise with
 *     no vault content — the PIN is required again.
 *  2. **Backing out** of the vault home (real back key): the disguise and the unlocked
 *     vault must not share a back stack, so back at the home root exits the app to the
 *     device home screen with the vault locked (APP-248) — it must NOT pop back to the
 *     calculator in an unlocked in-app session — and reopening then demands the PIN.
 *
 * Driven via UiAutomator (accessibility tree, unaffected by FLAG_SECURE) rather than a
 * compose rule: the compose test environment re-hosts the window's recomposer on the test
 * clock, which breaks the production spine's own navigation effects — and this proof must
 * exercise the production composition, not a test-hosted one. Asserted on the live UI
 * tree + [VaultSession], never screenshots (board rule #2).
 */
@RunWith(AndroidJUnit4::class)
class RelockDoDTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val pin = "1234"

    @Before
    fun setUp() {
        DoDTestSupport.grantAllFilesAccess(context)
        // Provision an onboarded user directly in the real credential store so the gate
        // routes to the calculator disguise and the typed PIN resolves to the real vault.
        runBlocking {
            AuthGraph.credentialStore.setRealPin(pin)
            AuthGraph.credentialStore.completeOnboarding()
        }
        VaultSession.clear()
    }

    @After
    fun cleanUp() {
        VaultSession.clear()
    }

    @Test
    fun backgroundingForgetsSessionAndResumesOnCalculatorNotVault() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlockVaultViaCalculator()
            assertThat(VaultSession.passphrase).isEqualTo(pin)

            // Whole-app background: ActivityScenario stops the (only) activity, which is
            // exactly the ProcessLifecycleOwner ON_STOP that home-press / Recents produce.
            scenario.moveToState(Lifecycle.State.CREATED)
            // ProcessLifecycleOwner debounces ON_STOP (~700ms); the session must be
            // forgotten WHILE still backgrounded — before any resume.
            awaitSessionCleared()

            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitCalculatorLockScreen()
        }
    }

    @Test
    fun backingOutOfVaultHomeExitsToDeviceHomeWithVaultLockedThenPinRequiredOnReopen() {
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            unlockVaultViaCalculator()
            assertThat(VaultSession.passphrase).isEqualTo(pin)
            awaitVaultUnlocked()

            // Back at the vault-home root exits the app to the launcher (APP-248) — it must
            // NOT surface the calculator in an unlocked in-app session. Our task leaves the
            // foreground and the vault is locked (session forgotten + data key dropped).
            device.pressBack()
            awaitLeftForeground()
            awaitSessionCleared()
            assertThat(vaultIsUnlocked()).isFalse()

            // Reopening the (warm) app lands on the calculator disguise with no vault
            // content — the PIN is required again, never straight into the vault.
            reopenApp()
            awaitCalculatorLockScreen()
            assertThat(VaultSession.passphrase).isNull()
            assertThat(vaultIsUnlocked()).isFalse()
        }
    }

    /** Type the PIN on the real calculator keypad and land on the vault home. */
    private fun unlockVaultViaCalculator() {
        assertThat(device.wait(Until.hasObject(By.text("=")), 20_000)).isTrue()
        pin.forEach { digit ->
            val key = device.findObject(By.text(digit.toString()))
            assertThat(key).isNotNull()
            key.click()
        }
        device.findObject(By.text("="))!!.click()
        assertThat(device.wait(Until.hasObject(By.textContains("Photos")), 20_000)).isTrue()
    }

    /** Poll until [VaultSession] is forgotten (the re-lock), failing loudly on timeout. */
    private fun awaitSessionCleared() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && VaultSession.passphrase != null) {
            Thread.sleep(100)
        }
        assertThat(VaultSession.passphrase).isNull()
    }

    /** The resumed/settled UI is the calculator disguise with zero vault content. */
    private fun awaitCalculatorLockScreen() {
        assertThat(device.wait(Until.gone(By.textContains("Photos")), 15_000)).isTrue()
        assertThat(device.wait(Until.hasObject(By.text("=")), 15_000)).isTrue()
    }

    /** True while the device-backed vault repository holds a live data key. */
    private fun vaultIsUnlocked(): Boolean =
        (VaultGraph.contentRepository as EncryptedVaultContentRepository).isUnlocked()

    /** Poll until the async [unlock] has derived the data key, failing loudly on timeout. */
    private fun awaitVaultUnlocked() {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !vaultIsUnlocked()) {
            Thread.sleep(100)
        }
        assertThat(vaultIsUnlocked()).isTrue()
    }

    /**
     * Poll until CalcVault is no longer the foreground package (APP-248): backing out of the
     * vault home must move the task to the background and reveal the device home / launcher —
     * NOT keep our own calculator in the foreground.
     */
    private fun awaitLeftForeground() {
        val ourPackage = context.packageName
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && device.currentPackageName == ourPackage) {
            Thread.sleep(100)
        }
        assertThat(device.currentPackageName).isNotEqualTo(ourPackage)
    }

    /**
     * Reopen the backgrounded app exactly as tapping the launcher icon would (APP-248).
     * `startActivity` from our own now-background process is blocked by Android's
     * background-activity-launch restrictions (API 29+), so drive the LAUNCHER intent from
     * the shell via `monkey` — it runs as the shell uid and brings the disguise entry
     * (the launcher-declared alias) to the foreground, warm-resuming the existing task.
     */
    private fun reopenApp() {
        device.executeShellCommand(
            "monkey -p ${context.packageName} -c android.intent.category.LAUNCHER 1",
        )
    }
}
