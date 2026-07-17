package com.appblish.calculatorvault.vault.documents

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.appblish.calculatorvault.vault.media.DateLabels
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem

/**
 * SAF document import (APP-527 §5 seam 2). Documents live anywhere on the device — not in a
 * MediaStore media collection — so they are picked with `ActivityResultContracts
 * .OpenMultipleDocuments()` and reach the vault as `content://` URIs. This is the bridge
 * from one picked URI to a staged [VaultItem] the shared `repository.hide(...)` path can
 * stream-encrypt: it resolves the file's real name, MIME, and size from the resolver, then
 * builds the staging record. The `hide()` pipeline is category-agnostic (it opens the
 * source via `resolver.openInputStream(content://)` when there is no public file path), so
 * documents reuse the exact same encrypt-then-remove flow as photos/videos — we only skip
 * the video poster/preview/thumbnail branches (there is no image to decode).
 */
object DocumentImport {
    /** Resolved facts about a picked document, before staging. */
    data class Meta(
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
    )

    /** The MIME OpenMultipleDocuments filters on — every document type is offered. */
    val PICK_MIME_TYPES: Array<String> = arrayOf("*/*")

    /**
     * Resolve [uri]'s display name, MIME, and size from [resolver]. Falls back to the URI's
     * last path segment for the name (SAF always exposes DISPLAY_NAME, but a defensive
     * fallback keeps a nameless provider from producing a blank vault entry). Returns null
     * only when the URI can't be queried at all (revoked grant / gone) so the caller skips
     * it rather than vaulting an empty record.
     */
    fun resolve(
        resolver: ContentResolver,
        uri: Uri,
    ): Meta? {
        val mime = resolver.getType(uri)
        var name: String? = null
        var size = 0L
        val cursor =
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            }.getOrNull()
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        val resolvedName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')
        return if (resolvedName.isNullOrBlank()) null else Meta(resolvedName, mime, size)
    }

    /**
     * Build the staged [VaultItem] the [VaultCategory.FILES] hide path will encrypt. Pure and
     * testable: [uriString] is carried as [VaultItem.sourceUri] so `hide()` can open the
     * bytes; the added-to-vault [now] drives both the recency sort key and the date-section
     * label. `sizeBytes` here is the *original* size — `hide()` overwrites it with the
     * encrypted blob size once stored, exactly as it does for media.
     */
    fun stage(
        meta: Meta,
        uriString: String,
        now: Long,
        folderId: String? = null,
    ): VaultItem =
        VaultItem(
            id = "",
            category = VaultCategory.FILES,
            originalName = meta.displayName,
            dateLabel = DateLabels.forEpochMillis(now),
            sortKey = now,
            folderId = folderId,
            sizeBytes = meta.sizeBytes,
            sourceUri = uriString,
            mimeType = meta.mimeType,
            dateModifiedMs = now,
        )
}
