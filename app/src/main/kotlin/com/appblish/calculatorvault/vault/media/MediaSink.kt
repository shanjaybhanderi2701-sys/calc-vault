package com.appblish.calculatorvault.vault.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import java.io.File

/**
 * The un-hide (restore-to-gallery) counterpart of [MediaSource]: writes a vault item's
 * decrypted bytes back to *public* storage so the file returns to the exact place the
 * user hid it from, and the system gallery re-indexes it.
 *
 * This realizes the board's [vault-technique §4] "watch it return to the gallery" beat:
 * hide strips the original from public storage into an app-private encrypted blob;
 * un-hide reverses it — decrypt → publish under the original RELATIVE_PATH + name → the
 * OS media scanner surfaces it in Photos/Files again.
 *
 * Two write paths by API level:
 *  - **API 29+ (scoped storage):** insert a fresh MediaStore row carrying DISPLAY_NAME,
 *    MIME_TYPE and RELATIVE_PATH, stream the bytes into its `openOutputStream`, then clear
 *    `IS_PENDING`. MediaStore indexes it automatically — no separate scan needed, and no
 *    storage permission is required for our own inserts.
 *  - **API ≤28 (legacy):** write a real file under the public external dir resolved from
 *    the relative path, then hand it to [MediaScannerConnection.scanFile] so the gallery
 *    picks it up. Needs `WRITE_EXTERNAL_STORAGE` (declared `maxSdkVersion=28`).
 *
 * [writeBack] returns the published content/file Uri on success, or null on failure so
 * the caller keeps the encrypted blob (never lose the only copy on a failed restore).
 */
class MediaSink(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    /**
     * Publish [bytes] back to public storage as [item]'s original file. Returns the new
     * public Uri, or null if the write failed (caller should then keep the vault copy).
     */
    fun writeBack(
        item: VaultItem,
        bytes: ByteArray,
    ): Uri? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(item, bytes)
            } else {
                writeViaLegacyFile(item, bytes)
            }
        } catch (e: Exception) {
            null
        }

    // --- API 29+ : MediaStore insert with IS_PENDING, auto-indexed ---

    private fun writeViaMediaStore(
        item: VaultItem,
        bytes: ByteArray,
    ): Uri? {
        val collection = collection(item.category)
        val relPath = item.relativePath?.takeIf { it.isNotBlank() } ?: defaultRelativePath(item.category)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.originalName)
                item.mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no output stream for $uri")
            val clear = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, clear, null, null)
            uri
        } catch (e: Exception) {
            // Roll back the half-written pending row so we don't leave an orphan entry.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    // --- API ≤28 : legacy public file + explicit media scan ---

    private fun writeViaLegacyFile(
        item: VaultItem,
        bytes: ByteArray,
    ): Uri? {
        val relPath = item.relativePath?.takeIf { it.isNotBlank() } ?: defaultRelativePath(item.category)
        val dir = File(Environment.getExternalStorageDirectory(), relPath).apply { mkdirs() }
        val target = uniqueFile(dir, item.originalName)
        target.outputStream().use { it.write(bytes) }
        val mimeTypes = item.mimeType?.let { arrayOf(it) }
        MediaScannerConnection.scanFile(appContext, arrayOf(target.absolutePath), mimeTypes, null)
        return Uri.fromFile(target)
    }

    /** Avoid clobbering an existing file: IMG.jpg → IMG (1).jpg → IMG (2).jpg … */
    private fun uniqueFile(
        dir: File,
        name: String,
    ): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val next = File(dir, "$stem ($i)$ext")
            if (!next.exists()) return next
            i++
        }
    }

    private fun collection(category: VaultCategory): Uri =
        when (category) {
            VaultCategory.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            VaultCategory.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            VaultCategory.AUDIOS -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            // Files and un-hidden contact vCards both land in Downloads on API 29+.
            VaultCategory.FILES, VaultCategory.CONTACTS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Files.getContentUri("external")
                }
        }

    private companion object {
        /** Per-category public folder used when the original's RELATIVE_PATH is unknown. */
        fun defaultRelativePath(category: VaultCategory): String =
            when (category) {
                VaultCategory.PHOTOS -> "${Environment.DIRECTORY_DCIM}/Restored/"
                VaultCategory.VIDEOS -> "${Environment.DIRECTORY_MOVIES}/Restored/"
                VaultCategory.AUDIOS -> "${Environment.DIRECTORY_MUSIC}/Restored/"
                VaultCategory.FILES, VaultCategory.CONTACTS -> "${Environment.DIRECTORY_DOWNLOADS}/Restored/"
            }
    }
}
