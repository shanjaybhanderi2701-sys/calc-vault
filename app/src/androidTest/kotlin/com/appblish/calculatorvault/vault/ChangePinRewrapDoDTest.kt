package com.appblish.calculatorvault.vault

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.auth.InMemoryCredentialStore
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.settings.ChangePinStage
import com.appblish.calculatorvault.settings.ChangePinViewModel
import com.appblish.calculatorvault.vault.crypto.VaultKeyFileReKeyer
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 data-safety DoD proof for the change-PIN envelope re-key (APP-245): hide an item,
 * change the PIN through the real Settings flow (verify → new → confirm), and prove the
 * vault did NOT strand — (1) the item still lists **and decrypts** under the new PIN,
 * (2) the old PIN no longer resolves and no longer unlocks the vault, (3) the hidden
 * original stays gone from MediaStore throughout (the in-process "adb content query"
 * assertion). Before this fix, step (1) failed: `setRealPin` rotated the auth token while
 * `.vaultkey` still wrapped the DEK under the old PIN, so the new PIN opened an empty
 * vault. Runs on the CI instrumented matrix — no manual emulator screenshots.
 */
@RunWith(AndroidJUnit4::class)
class ChangePinRewrapDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_rekey"
    private val displayName = "calcvault_dod_rekey_${System.nanoTime()}.jpg"
    private val relativePath = "DCIM/CalcVaultDoD/"
    private val oldPin = "1111"
    private val newPin = "2222"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
    }

    @After
    fun cleanUp() {
        DoDTestSupport.deleteImageRows(context, displayName)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun changePinRewrapsTheEnvelopeSoHiddenItemsSurviveAndTheOldPinDies() =
        runBlocking {
            // ---- Hide an item under the OLD pin. ----
            VaultSession.begin(oldPin, namespace = namespace)
            val original = DoDTestSupport.sampleJpegBytes()
            val sourceUri = DoDTestSupport.insertPublicImage(context, displayName, relativePath, original)

            val repoBefore = EncryptedVaultContentRepository(context)
            repoBefore.unlock()
            DoDTestSupport.awaitUnlock(repoBefore)
            val staged =
                VaultItem(
                    id = "staged",
                    category = VaultCategory.PHOTOS,
                    originalName = displayName,
                    dateLabel = "Today",
                    sortKey = System.currentTimeMillis(),
                    sourceUri = sourceUri.toString(),
                    mimeType = "image/jpeg",
                    relativePath = relativePath,
                )
            val stored = repoBefore.hide(listOf(staged)).single()
            context.contentResolver.delete(sourceUri, null, null)
            assertThat(DoDTestSupport.imageRowCount(context, displayName)).isEqualTo(0)

            // ---- Change the PIN through the real Settings flow. ----
            val store = InMemoryCredentialStore().also { it.setRealPin(oldPin) }
            val reKeyer = VaultKeyFileReKeyer(context, namespace = namespace)
            val vm =
                InstrumentationRegistry.getInstrumentation().let {
                    lateinit var built: ChangePinViewModel
                    it.runOnMainSync { built = ChangePinViewModel(store, reKeyer) }
                    built
                }
            vm.onVerify(oldPin)
            awaitStage(vm, ChangePinStage.NEW)
            vm.onNew(newPin)
            awaitStage(vm, ChangePinStage.CONFIRM)
            vm.onConfirm(newPin)
            // The 2×120k-iteration PBKDF2 unwrap+rewrap takes a moment on a cold AVD.
            awaitStage(vm, ChangePinStage.DONE)

            // The credential rotated with the envelope: new PIN resolves, old PIN is dead.
            assertThat(store.resolve(newPin)).isEqualTo(VaultKind.Real)
            assertThat(store.resolve(oldPin)).isNull()

            // ---- The NEW pin opens the same vault: item lists and decrypts. ----
            VaultSession.begin(newPin, namespace = namespace)
            val repoAfter = EncryptedVaultContentRepository(context)
            repoAfter.unlock()
            DoDTestSupport.awaitUnlock(repoAfter)
            val listed = repoAfter.items(VaultCategory.PHOTOS).first()
            assertThat(listed.map { it.id }).contains(stored.id)
            assertThat(repoAfter.openDecrypted(stored.id)).isEqualTo(original)
            // And the original never reappeared in MediaStore.
            assertThat(DoDTestSupport.imageRowCount(context, displayName)).isEqualTo(0)

            // ---- The OLD pin no longer unlocks anything. ----
            VaultSession.begin(oldPin, namespace = namespace)
            val repoOldPin = EncryptedVaultContentRepository(context)
            repoOldPin.unlock()
            repeat(50) {
                assertThat(repoOldPin.isUnlocked()).isFalse()
                Thread.sleep(100)
            }
        }

    /** Poll the VM state on the instrumented main-dispatcher until [stage] (or fail loudly). */
    private fun awaitStage(
        vm: ChangePinViewModel,
        stage: ChangePinStage,
    ) {
        repeat(300) {
            if (vm.state.value.stage == stage) return
            if (vm.state.value.error != null) {
                throw AssertionError("Change-PIN flow surfaced: ${vm.state.value.error}")
            }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $stage; at ${vm.state.value.stage}")
    }
}
