package com.appblish.calculatorvault.vault.storage

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Resolves the vault's on-disk layout in the **hidden public** `.CalcVault/` dot-folder on
 * shared storage (vault-technique §1–2, APP-142). Everything the vault persists lives here
 * — encrypted blobs, the encrypted metadata index, and the PIN-wrapped key file — so the
 * whole vault **survives app uninstall/reinstall**: `adb uninstall` (and the OS uninstall)
 * wipes app-private `filesDir`, but a top-level shared-storage dot-folder is left in place.
 *
 * Layout (all under `/storage/emulated/0/.CalcVault/`):
 * - `.nomedia`  — keeps the MediaStore scanner from indexing the blobs into Gallery.
 * - `<uuid>`    — one AES-256-GCM blob per hidden item. **UUID name, extension stripped**,
 *                 so nothing about the original file leaks from the file name.
 * - `index.enc` — the metadata index (names, categories, folders, bin), itself encrypted.
 * - `.vaultkey` — the PIN-wrapped data key (see `VaultKeyFile`).
 *
 * Writing this folder requires All Files Access (API 30+) or legacy external storage
 * (API 29-), gated by [StoragePermissions] at the point of need.
 */
object VaultStorage {
    const val DIR_NAME = ".CalcVault"
    const val INDEX_NAME = "index.enc"
    const val KEY_NAME = ".vaultkey"
    private const val NOMEDIA = ".nomedia"

    /**
     * The public `.CalcVault/` directory, created on first access along with its `.nomedia`
     * marker. Uses [Environment.getExternalStorageDirectory] (the shared-storage root)
     * deliberately: the dot-folder must sit outside our app-scoped dirs to outlive uninstall.
     */
    fun vaultDir(@Suppress("UNUSED_PARAMETER") context: Context): File {
        val dir = File(Environment.getExternalStorageDirectory(), DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        ensureNoMedia(dir)
        return dir
    }

    /** The encrypted-index file inside the vault dir. */
    fun indexFile(context: Context): File = File(vaultDir(context), INDEX_NAME)

    /** The PIN-wrapped key file inside the vault dir. */
    fun keyFile(context: Context): File = File(vaultDir(context), KEY_NAME)

    /** The blob file for [blobName] (a bare UUID, no extension) inside the vault dir. */
    fun blobFile(context: Context, blobName: String): File = File(vaultDir(context), blobName)

    private fun ensureNoMedia(dir: File) {
        val marker = File(dir, NOMEDIA)
        if (!marker.exists()) runCatching { marker.createNewFile() }
    }
}
