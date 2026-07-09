package com.appblish.calculatorvault.vault.storage

import android.content.Context
import android.os.Environment
import com.appblish.calculatorvault.vault.VaultSession
import java.io.File

/**
 * Resolves the vault's on-disk layout in the **hidden public** `.CalcVault/` dot-folder on
 * shared storage (vault-technique §1–2, APP-142). Everything the vault persists lives here
 * — encrypted blobs, the encrypted metadata index, and the PIN-wrapped key file — so the
 * whole vault **survives app uninstall/reinstall**: `adb uninstall` (and the OS uninstall)
 * wipes app-private `filesDir`, but a top-level shared-storage dot-folder is left in place.
 *
 * Layout (all under `/storage/emulated/0/.CalcVault/`):
 * - `.nomedia`  — keeps the MediaStore scanner from indexing the blobs into Gallery. Lives
 *                 at the root so it also covers every namespaced sub-directory below.
 * - `<uuid>`    — one AES-256-GCM blob per hidden item. **UUID name, extension stripped**,
 *                 so nothing about the original file leaks from the file name.
 * - `index.enc` — the metadata index (names, categories, folders, bin), itself encrypted.
 * - `.vaultkey` — the PIN-wrapped data key (see `VaultKeyFile`).
 *
 * **Vault namespacing (Phase 6, decoy isolation).** The real vault uses the root folder
 * (empty namespace), so it stays byte-compatible with the approved survive-uninstall gate
 * (APP-169). Each decoy passphrase resolves (via `VaultKind` → [VaultSession.namespace]) to
 * its own `decoy_<slot>/` sub-directory with an independent `.vaultkey` + `index.enc` +
 * blobs. Because a decoy's data key is wrapped under the decoy passphrase in a *different*
 * key file, a decoy session can never derive the real vault's key or read its index — the
 * spaces are isolated by both directory *and* key.
 *
 * Writing this folder requires All Files Access (API 30+) or legacy external storage
 * (API 29-), gated by [StoragePermissions] at the point of need.
 */
object VaultStorage {
    const val DIR_NAME = ".CalcVault"
    const val INDEX_NAME = "index.enc"
    const val KEY_NAME = ".vaultkey"
    const val RECOVERY_BACKOFF_NAME = ".recovery_backoff"
    private const val NOMEDIA = ".nomedia"

    /**
     * The public `.CalcVault/` (or `.CalcVault/<namespace>/`) directory for the active
     * session, created on first access. The root `.nomedia` marker is always ensured so the
     * MediaStore scanner skips the whole tree. Uses [Environment.getExternalStorageDirectory]
     * (the shared-storage root) deliberately: the dot-folder must sit outside our app-scoped
     * dirs to outlive uninstall.
     *
     * [namespace] defaults to the current [VaultSession.namespace]; pass it explicitly (e.g.
     * in tests) to target a specific vault.
     */
    fun vaultDir(
        @Suppress("UNUSED_PARAMETER") context: Context,
        namespace: String = VaultSession.namespace,
    ): File = vaultDir(Environment.getExternalStorageDirectory(), namespace)

    /**
     * Pure-JVM resolution of the vault directory under [baseDir] for [namespace] — no Android
     * dependency, so decoy-isolation and layout can be unit-tested off-device. Ensures the
     * root `.nomedia` and creates the (possibly namespaced) directory.
     */
    fun vaultDir(
        baseDir: File,
        namespace: String,
    ): File {
        val root = File(baseDir, DIR_NAME)
        if (!root.exists()) root.mkdirs()
        ensureNoMedia(root)
        val dir = if (namespace.isBlank()) root else File(root, namespace).also { if (!it.exists()) it.mkdirs() }
        return dir
    }

    /** The encrypted-index file inside the active vault dir. */
    fun indexFile(
        context: Context,
        namespace: String = VaultSession.namespace,
    ): File = File(vaultDir(context, namespace), INDEX_NAME)

    /** The PIN-wrapped key file inside the active vault dir. */
    fun keyFile(
        context: Context,
        namespace: String = VaultSession.namespace,
    ): File = File(vaultDir(context, namespace), KEY_NAME)

    /**
     * The recovery-entry brute-force backoff counters (PIN Recovery §1.6), stored beside the
     * key file so they **survive uninstall** with the vault they guard — a reinstall must not
     * reset the lockout. See [com.appblish.calculatorvault.vault.crypto.FileRecoveryBackoffStore].
     */
    fun recoveryBackoffFile(
        context: Context,
        namespace: String = VaultSession.namespace,
    ): File = File(vaultDir(context, namespace), RECOVERY_BACKOFF_NAME)

    /** The blob file for [blobName] (a bare UUID, no extension) inside the active vault dir. */
    fun blobFile(
        context: Context,
        blobName: String,
        namespace: String = VaultSession.namespace,
    ): File = File(vaultDir(context, namespace), blobName)

    /**
     * The **encrypted** stored-thumbnail file for [blobName] (APP-244), under a `thumbs/`
     * sub-directory of the active vault dir so it shares the blob's namespace isolation,
     * survive-uninstall location, and `.nomedia` cover. Same bare-UUID name as its blob —
     * nothing about the original leaks — and the content is a VaultCrypto ciphertext,
     * never a plaintext preview.
     */
    fun thumbFile(
        context: Context,
        blobName: String,
        namespace: String = VaultSession.namespace,
    ): File {
        val dir = File(vaultDir(context, namespace), THUMBS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, blobName)
    }

    const val THUMBS_DIR = "thumbs"

    private fun ensureNoMedia(dir: File) {
        val marker = File(dir, NOMEDIA)
        if (!marker.exists()) runCatching { marker.createNewFile() }
    }
}
