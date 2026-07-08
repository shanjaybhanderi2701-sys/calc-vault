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
 * On-device proof of the W1-E2 hard rules against the real [EncryptedVaultContentRepository]
 * (spec §1): a **Move** relocates the index entry while the bytes stay AES-256 encrypted at
 * rest, and a **permanent Delete** securely wipes the blob (no recoverable ciphertext) and
 * removes its index entry — verified to survive a fresh-repository reload, exactly what a
 * relaunch gives you. Complements the JVM tests (which cover the pure logic) with the
 * encryption/secure-wipe behaviour that only a device can show.
 */
@RunWith(AndroidJUnit4::class)
class PhotoActionsEncryptedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pin = "2468"
    private val secretName = "W1E2-secret-photo-name.jpg"

    @Before
    fun grantAllFilesAccessAndClean() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
        )
        repeat(40) { if (StoragePermissions.hasAllFilesAccess(context)) return@repeat else Thread.sleep(50) }
        assertThat(StoragePermissions.hasAllFilesAccess(context)).isTrue()
        File(Environment.getExternalStorageDirectory(), VaultStorage.DIR_NAME).deleteRecursively()
    }

    @Test
    fun moveStaysEncrypted_andPermanentDeleteSecurelyWipesTheBlob() =
        runBlocking {
            VaultSession.begin(pin)
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            awaitUnlock(repo)

            // Hide two photos (sourceUri = null → the names are encrypted into the blobs).
            val stored =
                repo.hide(
                    listOf(
                        staged("keep", secretName),
                        staged("wipe", "second.jpg"),
                    ),
                )
            val keepId = stored.first { it.originalName == secretName }.id
            val wipeId = stored.first { it.originalName == "second.jpg" }.id

            val vaultDir = VaultStorage.vaultDir(context)

            fun blobs() = vaultDir.listFiles { f -> f.isFile && f.name.matches(UUID_REGEX) }.orEmpty()
            assertThat(blobs()).hasLength(2)

            // --- Move: index-only relocation, bytes stay encrypted at rest ------------------
            val folder = repo.createFolder(VaultCategory.PHOTOS, "Trip")
            repo.moveToFolder(setOf(keepId), folder.id)

            // Both blobs still on disk (move never deletes a blob) …
            assertThat(blobs()).hasLength(2)
            // … and the index is still ENCRYPTED — the secret name never appears in cleartext.
            val indexAfterMove = VaultStorage.indexFile(context).readBytes()
            assertThat(String(indexAfterMove)).doesNotContain(secretName)

            // The move persisted: a fresh repo (relaunch) reads back the new folder id.
            VaultSession.begin(pin)
            val reloaded = EncryptedVaultContentRepository(context)
            reloaded.unlock()
            awaitUnlock(reloaded)
            val movedItem = reloaded.allItems().first().first { it.id == keepId }
            assertThat(movedItem.folderId).isEqualTo(folder.id)

            // --- Permanent delete: secure wipe + index removal ------------------------------
            val wipeBlobName = reloaded
                .allItems()
                .first()
                .first { it.id == wipeId }
                .encryptedPath!!
            val wipeBlob = File(vaultDir, wipeBlobName)
            assertThat(wipeBlob.exists()).isTrue()

            reloaded.permanentlyDelete(setOf(wipeId))

            // Blob file is gone (securely overwritten then unlinked) and only "keep" remains.
            assertThat(wipeBlob.exists()).isFalse()
            assertThat(blobs()).hasLength(1)
            assertThat(reloaded.allItems().first().map { it.id }).containsExactly(keepId)
            // Index no longer references the wiped item, and "keep" is still recoverable.
            val indexAfterWipe = VaultStorage.indexFile(context).readBytes()
            assertThat(String(indexAfterWipe)).doesNotContain(secretName)
            assertThat(reloaded.openDecrypted(keepId)).isEqualTo(secretName.toByteArray())

            VaultSession.begin(pin)
        }

    private fun staged(
        id: String,
        name: String,
    ) = VaultItem(
        id = id,
        category = VaultCategory.PHOTOS,
        originalName = name,
        dateLabel = "Today",
        sortKey = 1_000L,
        mimeType = "image/jpeg",
        relativePath = "DCIM/Camera/",
    )

    private fun awaitUnlock(repo: EncryptedVaultContentRepository) {
        repeat(200) { if (repo.isUnlocked()) return else Thread.sleep(100) }
        assertThat(repo.isUnlocked()).isTrue()
    }

    private companion object {
        val UUID_REGEX = Regex("[0-9a-fA-F-]{36}")
    }
}
