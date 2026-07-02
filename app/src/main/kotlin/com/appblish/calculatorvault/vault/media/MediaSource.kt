package com.appblish.calculatorvault.vault.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import com.appblish.calculatorvault.vault.SourceAlbum
import com.appblish.calculatorvault.vault.SourceItem
import com.appblish.calculatorvault.vault.model.VaultCategory
import java.util.Calendar

/**
 * Enumerates public-storage originals for the hide/import picker, grouped into albums
 * (MediaStore buckets) with date-labelled items. Backs Photos/Videos/Audios/Files via
 * [MediaStore] and Contacts via [ContactsContract] (exported as vCard). Every item
 * carries the real content [Uri] so the repository can stream and encrypt the bytes and
 * the UI can request deletion of the original.
 *
 * Callers must already hold the relevant runtime permission; a [SecurityException] from
 * the resolver surfaces as an empty result so the picker can show its permission state.
 */
class MediaSource(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    /** Albums (buckets) available for [category], each with an item count. */
    fun albums(category: VaultCategory): List<SourceAlbum> =
        try {
            when (category) {
                VaultCategory.CONTACTS -> contactAlbums()
                else -> mediaAlbums(category)
            }
        } catch (e: SecurityException) {
            emptyList()
        }

    /** Date-labelled items within [albumId] of [category], newest first. */
    fun sources(
        category: VaultCategory,
        albumId: String,
    ): List<SourceItem> =
        try {
            when (category) {
                VaultCategory.CONTACTS -> contactSources()
                else -> mediaSources(category, albumId)
            }
        } catch (e: SecurityException) {
            emptyList()
        }

    // --- MediaStore (photos / videos / audios / files) ---

    private fun collection(category: VaultCategory): Uri =
        when (category) {
            VaultCategory.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            VaultCategory.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            VaultCategory.AUDIOS -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            VaultCategory.FILES ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Files.getContentUri("external")
                }
            VaultCategory.CONTACTS -> error("contacts use ContactsContract")
        }

    private fun mediaAlbums(category: VaultCategory): List<SourceAlbum> {
        val counts = LinkedHashMap<String, Pair<String, Int>>() // bucketId -> (name, count)
        queryMedia(category, bucketId = null) { row ->
            val id = row.bucketId
            val existing = counts[id]
            counts[id] = (existing?.first ?: row.bucketName) to ((existing?.second ?: 0) + 1)
        }
        return counts.map { (id, nameCount) -> SourceAlbum(id, nameCount.first, nameCount.second) }
    }

    private fun mediaSources(
        category: VaultCategory,
        albumId: String,
    ): List<SourceItem> {
        val out = mutableListOf<SourceItem>()
        queryMedia(category, bucketId = albumId) { row ->
            out +=
                SourceItem(
                    id = row.uri.toString(),
                    name = row.name,
                    dateLabel = DateLabels.forEpochMillis(row.dateMillis),
                    sortKey = row.dateMillis,
                    albumId = row.bucketId,
                    albumName = row.bucketName,
                    contentUri = row.uri.toString(),
                    mimeType = row.mime,
                    relativePath = row.relativePath,
                )
        }
        return out.sortedByDescending { it.sortKey }
    }

    private data class MediaRow(
        val uri: Uri,
        val name: String,
        val dateMillis: Long,
        val bucketId: String,
        val bucketName: String,
        val mime: String?,
        val relativePath: String,
    )

    private inline fun queryMedia(
        category: VaultCategory,
        bucketId: String?,
        onRow: (MediaRow) -> Unit,
    ) {
        val idCol = MediaStore.MediaColumns._ID
        val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
        val dateCol = MediaStore.MediaColumns.DATE_ADDED
        val mimeCol = MediaStore.MediaColumns.MIME_TYPE
        val bucketIdCol = MediaStore.MediaColumns.BUCKET_ID
        val bucketNameCol = MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        // RELATIVE_PATH (the "DCIM/Camera/" style album path) only exists on API 29+.
        val relPathCol =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else null
        val projection =
            arrayOf(idCol, nameCol, dateCol, mimeCol, bucketIdCol, bucketNameCol) +
                (relPathCol?.let { arrayOf(it) } ?: emptyArray())
        val selection = if (bucketId != null) "$bucketIdCol = ?" else null
        val args = if (bucketId != null) arrayOf(bucketId) else null
        resolver.query(collection(category), projection, selection, args, "$dateCol DESC")?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(idCol)
            val nameIdx = c.getColumnIndexOrThrow(nameCol)
            val dateIdx = c.getColumnIndexOrThrow(dateCol)
            val mimeIdx = c.getColumnIndexOrThrow(mimeCol)
            val bIdIdx = c.getColumnIndex(bucketIdCol)
            val bNameIdx = c.getColumnIndex(bucketNameCol)
            val relPathIdx = if (relPathCol != null) c.getColumnIndex(relPathCol) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val bId = if (bIdIdx >= 0 && !c.isNull(bIdIdx)) c.getString(bIdIdx) else "all"
                val bName =
                    if (bNameIdx >= 0 && !c.isNull(bNameIdx)) c.getString(bNameIdx) else "All"
                val relPath =
                    if (relPathIdx >= 0 && !c.isNull(relPathIdx)) c.getString(relPathIdx) else ""
                onRow(
                    MediaRow(
                        uri = ContentUris.withAppendedId(collection(category), id),
                        name = c.getString(nameIdx) ?: "item_$id",
                        dateMillis = c.getLong(dateIdx) * 1000L,
                        bucketId = bId,
                        bucketName = bName,
                        mime = if (c.isNull(mimeIdx)) null else c.getString(mimeIdx),
                        relativePath = relPath,
                    ),
                )
            }
        }
    }

    /**
     * Delete the public source contacts behind [vcardUris] (the vCard content Uris the
     * picker handed out). Unlike MediaStore items, contacts have no system delete-consent
     * dialog, so this deletes directly via [ContactsContract] and needs `WRITE_CONTACTS`.
     * Each vCard Uri's last path segment is the contact lookup key, which resolves to the
     * aggregate contact's lookup Uri; deleting that removes all its raw contacts. Returns
     * the number of contacts removed; a missing permission surfaces as 0 (nothing deleted).
     */
    fun deleteContacts(vcardUris: List<String>): Int =
        try {
            var deleted = 0
            for (raw in vcardUris) {
                val lookupKey = runCatching { Uri.parse(raw).lastPathSegment }.getOrNull() ?: continue
                val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
                deleted += runCatching { resolver.delete(lookupUri, null, null) }.getOrDefault(0)
            }
            deleted
        } catch (e: SecurityException) {
            0
        }

    // --- Contacts (exported as vCard) ---

    private fun contactAlbums(): List<SourceAlbum> {
        var count = 0
        resolver
            .query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null,
            )?.use { count = it.count }
        return listOf(SourceAlbum("phone", "Phone contacts", count))
    }

    private fun contactSources(): List<SourceItem> {
        val out = mutableListOf<SourceItem>()
        resolver
            .query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME,
                ),
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val lookupIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                var order = 0L
                while (c.moveToNext()) {
                    val lookup = c.getString(lookupIdx) ?: continue
                    val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookup)
                    out +=
                        SourceItem(
                            id = c.getString(idIdx),
                            name = c.getString(nameIdx) ?: "Contact",
                            dateLabel = "Contacts",
                            sortKey = order++,
                            albumId = "phone",
                            albumName = "Phone contacts",
                            contentUri = vcardUri.toString(),
                            mimeType = "text/vcard",
                        )
                }
            }
        return out
    }
}

/** Formats epoch millis into the deck's date-section labels. */
object DateLabels {
    fun forEpochMillis(millis: Long): String {
        if (millis <= 0L) return "Unknown"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }

        fun sameDay(offset: Int): Boolean {
            val ref = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
            return ref.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                ref.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        }
        return when {
            sameDay(0) -> "Today"
            sameDay(-1) -> "Yesterday"
            else -> {
                val months =
                    arrayOf(
                        "Jan",
                        "Feb",
                        "Mar",
                        "Apr",
                        "May",
                        "Jun",
                        "Jul",
                        "Aug",
                        "Sep",
                        "Oct",
                        "Nov",
                        "Dec",
                    )
                "${then.get(Calendar.DAY_OF_MONTH)} ${months[then.get(Calendar.MONTH)]} ${then.get(Calendar.YEAR)}"
            }
        }
    }
}
