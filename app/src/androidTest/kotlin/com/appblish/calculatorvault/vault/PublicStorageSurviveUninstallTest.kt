package com.appblish.calculatorvault.vault

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof of the APP-169 Phase-2 gate: encrypted content written to the hidden
 * public `.CalcVault/` folder **survives a fresh install** and is **recoverable with the
 * PIN**, while a wrong PIN cannot open it.
 *
 * The test never touches app-private storage. It grants All Files Access via appops (the
 * same permission the primer requests), writes a blob through the real repository, then
 * constructs a brand-new repository instance — which is exactly what a reinstall gives you:
 * an empty in-memory state pointed at the same shared-storage folder — and shows the item
 * comes back and decrypts to the original bytes only when the PIN matches.
 */
@RunWith(AndroidJUnit4::class)
class PublicStorageSurviveUninstallTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pin = "1234"
    private val wrongPin = "9999"
    private val payload = "APP-169 survive-uninstall proof :: secret bytes 42".toByteArray()

    @Before
    fun grantAllFilesAccessAndClean() {
        // Grant MANAGE_EXTERNAL_STORAGE the way the OS special-access screen would.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
        )
        // executeShellCommand is async; wait for the grant to take effect.
        repeat(40) { if (StoragePermissions.hasAllFilesAccess(context)) return@repeat else Thread.sleep(50) }
        assertThat(StoragePermissions.hasAllFilesAccess(context)).isTrue()
        // Start from a clean folder so the run is self-contained.
        File(Environment.getExternalStorageDirectory(), VaultStorage.DIR_NAME).deleteRecursively()
    }

    @Test
    fun hiddenContentSurvivesReinstallAndOpensWithPinOnly() =
        runBlocking {
            // --- Install #1: set up the vault and hide one item -----------------------------
            VaultSession.begin(pin)
            val first = EncryptedVaultContentRepository(context)
            first.unlock()
            awaitUnlock(first)

            // sourceUri = null → the repository encrypts these bytes directly into a blob.
            val staged =
                VaultItem(
                    id = "staged",
                    category = VaultCategory.FILES,
                    originalName = String(payload),
                    dateLabel = "today",
                    sortKey = 1_000L,
                )
            val stored = first.hide(listOf(staged))
            val storedId = stored.single().id

            // Everything lives in the hidden PUBLIC folder, with a .nomedia marker.
            val vaultDir = VaultStorage.vaultDir(context)
            assertThat(vaultDir.absolutePath).contains("/.CalcVault")
            assertThat(File(vaultDir, ".nomedia").exists()).isTrue()
            assertThat(VaultStorage.keyFile(context).exists()).isTrue()
            assertThat(VaultStorage.indexFile(context).exists()).isTrue()
            // A blob with a bare UUID name (no extension).
            val blobs = vaultDir.listFiles { f -> f.isFile && f.name.matches(UUID_REGEX) }.orEmpty()
            assertThat(blobs).hasLength(1)
            // The index is ENCRYPTED — the original name must not be readable on disk.
            val indexBytes = VaultStorage.indexFile(context).readBytes()
            assertThat(String(indexBytes)).doesNotContain("survive-uninstall proof")

            // --- "Reinstall": a fresh repository over the same shared-storage folder ---------
            // A new instance starts with empty in-memory state and no keystore material — the
            // only way it can read anything is the public key file + encrypted index.
            VaultSession.begin(pin)
            val reinstalled = EncryptedVaultContentRepository(context)
            reinstalled.unlock()
            awaitUnlock(reinstalled)

            val recovered = reinstalled.allItems().first()
            assertThat(recovered.map { it.id }).contains(storedId)
            val bytes = reinstalled.openDecrypted(storedId)
            assertThat(bytes).isEqualTo(payload)

            // --- Wrong PIN cannot open the same folder --------------------------------------
            VaultSession.begin(wrongPin)
            val attacker = EncryptedVaultContentRepository(context)
            attacker.unlock()
            // Let the attempt fully run (PBKDF2 derive + GCM verify); it must fail and stay locked.
            Thread.sleep(4_000)
            assertThat(attacker.isUnlocked()).isFalse()
            assertThat(attacker.allItems().first()).isEmpty()

            VaultSession.begin(pin)
        }

    private fun awaitUnlock(repo: EncryptedVaultContentRepository) {
        // Generous: first-run key creation runs 120k-iteration PBKDF2, slow on a cold emulator.
        repeat(200) { if (repo.isUnlocked()) return else Thread.sleep(100) }
        assertThat(repo.isUnlocked()).isTrue()
    }

    private companion object {
        val UUID_REGEX = Regex("[0-9a-fA-F-]{36}")
    }
}
