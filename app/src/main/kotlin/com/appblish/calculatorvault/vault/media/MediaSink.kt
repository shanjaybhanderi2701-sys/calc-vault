package com.appblish.calculatorvault.vault.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.UnhideDisposition
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import java.io.File
import java.io.OutputStream

/**
 * The un-hide (restore-to-gallery) counterpart of [MediaSource]: writes a vault item's
 * decrypted bytes back to *public* storage so the file returns to the exact place the
 * user hid it from, and the system gallery re-indexes it.
 *
 * This realizes the board's [vault-technique §4] "watch it return to the gallery" beat:
 * hide strips the original from public storage into an app-private encrypted blob;
 * un-hide reverses it — decrypt → publish under the requested RELATIVE_PATH + name → the
 * OS media scanner surfaces it in Photos/Files again.
 *
 * **Fallback (spec §1.4 — never fail silently):** the caller picks a destination (the
 * original album, or a chosen folder). If that primary write fails (folder unavailable /
 * unwritable), [writeBack] retries once against **Downloads** and reports the fallback so
 * the UI can tell the user where the file actually landed. Only when even Downloads is
 * unwritable does it return [UnhideDisposition.FAILED] with a null Uri, so the caller
 * keeps the encrypted vault copy (never lose the only copy on a failed restore).
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
 * Plaintext arrives via a writer lambda, not a ByteArray, so the repository can stream
 * blob → cipher → public storage without materializing a large video in memory.
 */
class MediaSink(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    /** Where a single item landed on un-hide, and the human label of that folder. */
    data class WriteBackResult(
        val uri: Uri?,
        val disposition: UnhideDisposition,
        val destinationLabel: String?,
    )

    /**
     * Legacy convenience: publish [bytes] to [item]'s original location, returning the new
     * public Uri or null on total failure. Prefer [writeBack] with an explicit
     * [UnhideDestination] so fallbacks are reported instead of applied silently.
     */
    fun writeBack(
        item: VaultItem,
        bytes: ByteArray,
    ): Uri? = writeBack(item, bytes, UnhideDestination.Original).uri

    /** Byte-array convenience for small payloads and tests: wraps the streaming [writeBack]. */
    fun writeBack(
        item: VaultItem,
        bytes: ByteArray,
        destination: UnhideDestination,
    ): WriteBackResult = writeBack(item, destination) { out -> out.write(bytes) }

    /**
     * Publish a vault item back to public storage at [destination], streaming the plaintext
     * from [writer] (typically blob → VaultCrypto.decrypt → this output stream), falling
     * back to Downloads if the primary destination is unwritable. Returns which of the
     * three outcomes occurred plus the folder label for the user-facing message.
     *
     * NOTE: a [writer] failure (e.g. a bad GCM tag on decrypt) fails the primary attempt
     * and then fails the Downloads retry the same way — acceptable double cost on a rare
     * path, and it keeps "unwritable destination" and "unreadable blob" on the same safe
     * FAILED exit where the caller retains the encrypted blob.
     */
    fun writeBack(
        item: VaultItem,
        destination: UnhideDestination,
        writer: (OutputStream) -> Unit,
    ): WriteBackResult {
        val chosen = destination as? UnhideDestination.Chosen
        val primary = primaryRelPath(item, destination)
        if (primary != null) {
            val uri = writeAt(item, primary, writer)
            if (uri != null) {
                return WriteBackResult(uri, UnhideDisposition.REQUESTED, chosen?.label ?: folderLabel(primary))
            }
        }
        // A chosen folder the RELATIVE_PATH route couldn't serve (non-primary volume, or a
        // primary directory MediaStore rejects for the media collection) is still honored by
        // writing straight into the picked SAF tree (APP-293 P0-2) — the user's choice wins
        // over a silent Downloads degrade.
        val tree = chosen?.treeUri
        if (tree != null) {
            val uri = writeViaSafTree(item, tree, writer)
            if (uri != null) {
                return WriteBackResult(uri, UnhideDisposition.REQUESTED, chosen.label ?: CHOSEN_LABEL)
            }
        }
        // Primary unavailable/unwritable (or unknown original) → fall back to Downloads and
        // say so, rather than dropping the file or the message (spec §1.4).
        val fallback = fallbackRelPath()
        if (fallback != primary) {
            val uri = writeAt(item, fallback, writer)
            if (uri != null) {
                return WriteBackResult(uri, UnhideDisposition.FALLBACK, FALLBACK_LABEL)
            }
        }
        return WriteBackResult(null, UnhideDisposition.FAILED, null)
    }

    /** The requested destination's RELATIVE_PATH, or null when the original is unknown. */
    private fun primaryRelPath(
        item: VaultItem,
        destination: UnhideDestination,
    ): String? =
        when (destination) {
            is UnhideDestination.Chosen -> destination.relativePath?.takeIf { it.isNotBlank() }
            UnhideDestination.Original -> item.relativePath?.takeIf { it.isNotBlank() }
        }

    /**
     * Write into the user-picked SAF tree via DocumentsContract: create a child document
     * under the tree root and stream the plaintext into it. The provider uniquifies a
     * colliding display name itself ("IMG.jpg (1)"), matching the spec §2.4 name-collision
     * contract. A half-written document is deleted before returning null so a mid-stream
     * decrypt failure leaves no partial plaintext behind.
     */
    private fun writeViaSafTree(
        item: VaultItem,
        treeUriString: String,
        writer: (OutputStream) -> Unit,
    ): Uri? =
        try {
            val treeUri = Uri.parse(treeUriString)
            val parentDoc =
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            val doc =
                DocumentsContract.createDocument(
                    resolver,
                    parentDoc,
                    item.mimeType ?: DEFAULT_MIME,
                    item.originalName,
                ) ?: error("createDocument returned null for $parentDoc")
            try {
                resolver.openOutputStream(doc)?.use(writer) ?: error("no output stream for $doc")
                doc
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                null
            }
        } catch (e: Exception) {
            null
        }

    /** One write attempt at [relPath] via the API-appropriate path; null on any failure. */
    private fun writeAt(
        item: VaultItem,
        relPath: String,
        writer: (OutputStream) -> Unit,
    ): Uri? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(item, relPath, writer)
            } else {
                writeViaLegacyFile(item, relPath, writer)
            }
        } catch (e: Exception) {
            null
        }

    // --- API 29+ : MediaStore insert with IS_PENDING, auto-indexed ---

    private fun writeViaMediaStore(
        item: VaultItem,
        relPath: String,
        writer: (OutputStream) -> Unit,
    ): Uri? {
        val collection = collectionFor(item.category, relPath)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.originalName)
                item.mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use(writer) ?: error("no output stream for $uri")
            val clear = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, clear, null, null)
            uri
        } catch (e: Exception) {
            // Roll back the half-written pending row so we don't leave an orphan entry
            // (also discards partial plaintext from a mid-stream decrypt failure).
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    // --- API ≤28 : legacy public file + explicit media scan ---

    private fun writeViaLegacyFile(
        item: VaultItem,
        relPath: String,
        writer: (OutputStream) -> Unit,
    ): Uri? {
        val dir = File(Environment.getExternalStorageDirectory(), relPath).apply { mkdirs() }
        val target = uniqueFile(dir, item.originalName)
        try {
            target.outputStream().use(writer)
        } catch (e: Exception) {
            // Discard the partial file (unauthenticated plaintext) before propagating.
            target.delete()
            throw e
        }
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

    /**
     * MediaStore rejects an insert whose RELATIVE_PATH's primary directory is not legal
     * for the collection — e.g. `Download/` into Images throws. The §7 Downloads fallback
     * must therefore go through the Downloads collection regardless of media category
     * (MediaStore still derives the media type from the MIME, so an image restored to
     * Download/ remains visible to gallery Images queries).
     */
    private fun collectionFor(
        category: VaultCategory,
        relPath: String,
    ): Uri =
        if (relPath.substringBefore('/') == Environment.DIRECTORY_DOWNLOADS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            collection(category)
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
        const val FALLBACK_LABEL = "Downloads"
        const val CHOSEN_LABEL = "chosen folder"
        const val DEFAULT_MIME = "application/octet-stream"

        /** The always-available fallback dir (spec §1.4 fallback target). */
        fun fallbackRelPath(): String = "${Environment.DIRECTORY_DOWNLOADS}/"

        /** Last non-empty path segment of a RELATIVE_PATH, e.g. "DCIM/Camera/" → "Camera". */
        fun folderLabel(relPath: String): String =
            relPath.trim('/').substringAfterLast('/').ifBlank { relPath.trim('/') }
    }
}
