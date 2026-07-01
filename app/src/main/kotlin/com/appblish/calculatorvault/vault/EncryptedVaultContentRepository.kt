package com.appblish.calculatorvault.vault

import android.content.Context
import android.net.Uri
import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import com.appblish.calculatorvault.vault.crypto.VaultKeyStore
import com.appblish.calculatorvault.vault.model.RecycleBin
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Device [VaultContentRepository] that realizes the "encrypt + remove from public
 * storage" pipeline the Phase 2 seam described. It streams each picked original through
 * [VaultCrypto] into an app-private, AES-256-GCM blob under `filesDir/vault/blobs`,
 * records lightweight metadata in an on-disk JSON index, and serves decrypted bytes back
 * to the viewers. The vault data key is keystore-backed via [VaultKeyStore].
 *
 * Removal of the *public* original is completed by the hide/import UI via a
 * MediaStore delete-request (which needs user consent on API 30+, so it cannot run from
 * a repository without an Activity). [hide] therefore returns the stored items still
 * carrying their [VaultItem.sourceUri] so the caller can issue the delete request; the
 * persisted copies never retain the source Uri.
 *
 * All flows mirror an in-memory snapshot that is loaded from and written back to the
 * index file, so navigation across home, categories, viewers, and the recycle bin
 * observes one consistent state, and the vault survives process death.
 */
class EncryptedVaultContentRepository(
    context: Context,
) : VaultContentRepository {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val keyStore = VaultKeyStore(appContext)
    private val crypto by lazy { VaultCrypto(keyStore.secretKey()) }

    private val baseDir = File(appContext.filesDir, "vault").apply { mkdirs() }
    private val blobsDir = File(baseDir, "blobs").apply { mkdirs() }
    private val indexFile = File(baseDir, "index.json")

    private val mutex = Mutex()

    private val itemsState = MutableStateFlow<List<VaultItem>>(emptyList())
    private val foldersState = MutableStateFlow<List<VaultFolder>>(emptyList())
    private val binState = MutableStateFlow<List<RecycleBinEntry>>(emptyList())

    init {
        loadIndex()
    }

    override fun items(category: VaultCategory): Flow<List<VaultItem>> =
        itemsState.map { all -> all.filter { it.category == category }.sortedByDescending { it.sortKey } }

    override fun allItems(): Flow<List<VaultItem>> = itemsState.map { all -> all.sortedByDescending { it.sortKey } }

    override fun folders(category: VaultCategory): Flow<List<VaultFolder>> =
        foldersState.map { all -> all.filter { it.category == category } }

    override fun categoryCounts(): Flow<Map<VaultCategory, Int>> =
        itemsState.map { all -> VaultCategory.entries.associateWith { cat -> all.count { it.category == cat } } }

    override fun recent(limit: Int): Flow<List<VaultItem>> =
        itemsState.map { all -> all.sortedByDescending { it.sortKey }.take(limit) }

    override fun recycleBin(): Flow<List<RecycleBinEntry>> =
        binState.map { entries -> entries.sortedByDescending { it.deletedAt } }

    override suspend fun hide(items: List<VaultItem>): List<VaultItem> =
        withContext(Dispatchers.IO) {
            val stored = mutableListOf<VaultItem>()
            for (staged in items) {
                val id = "v${UUID.randomUUID()}"
                val blob = File(blobsDir, "$id.enc")
                val encryptedBytes =
                    try {
                        encryptSource(staged, blob)
                    } catch (e: Exception) {
                        // Skip an unreadable source rather than aborting the whole batch;
                        // the original is left in place (nothing to un-hide).
                        blob.delete()
                        continue
                    }
                val item =
                    staged.copy(
                        id = id,
                        encryptedPath = blob.absolutePath,
                        sizeBytes = encryptedBytes,
                        sourceUri = null,
                    )
                mutex.withLock {
                    itemsState.value = itemsState.value + item
                    persist()
                }
                // Return value keeps the source Uri so the UI can delete the public copy.
                stored += item.copy(sourceUri = staged.sourceUri)
            }
            stored
        }

    /** Stream the staged original through [VaultCrypto] into [blob]; returns blob size. */
    private fun encryptSource(
        staged: VaultItem,
        blob: File,
    ): Long {
        val uri = staged.sourceUri
        val input =
            if (uri != null) {
                resolver.openInputStream(Uri.parse(uri))
                    ?: error("Cannot open source $uri")
            } else {
                // No public source (e.g. a synthesized contact vCard passed as name bytes):
                // encrypt the original name so the blob is still a real ciphertext.
                staged.originalName.toByteArray().inputStream()
            }
        input.use { source ->
            blob.outputStream().use { sink -> crypto.encrypt(source, sink) }
        }
        return blob.length()
    }

    override suspend fun createFolder(
        category: VaultCategory,
        name: String,
    ): VaultFolder =
        mutex.withLock {
            val folder = VaultFolder(id = "f${UUID.randomUUID()}", category = category, name = name)
            foldersState.value = foldersState.value + folder
            persist()
            folder
        }

    override suspend fun moveToFolder(
        itemIds: Set<String>,
        folderId: String?,
    ) = mutex.withLock {
        itemsState.value = itemsState.value.map { if (it.id in itemIds) it.copy(folderId = folderId) else it }
        persist()
    }

    override suspend fun moveToRecycleBin(itemIds: Set<String>) =
        mutex.withLock {
            val (moved, kept) = itemsState.value.partition { it.id in itemIds }
            val now = System.currentTimeMillis()
            binState.value = binState.value + moved.map { RecycleBinEntry(it, deletedAt = now) }
            itemsState.value = kept
            persist()
        }

    override suspend fun restore(itemIds: Set<String>) =
        mutex.withLock {
            val (restored, kept) = binState.value.partition { it.item.id in itemIds }
            itemsState.value = itemsState.value + restored.map { it.item }
            binState.value = kept
            persist()
        }

    override suspend fun deleteForever(itemIds: Set<String>) =
        mutex.withLock {
            val (removed, kept) = binState.value.partition { it.item.id in itemIds }
            removed.forEach { it.item.encryptedPath?.let { path -> File(path).delete() } }
            binState.value = kept
            persist()
        }

    override suspend fun purgeExpired(now: Long): Int =
        mutex.withLock {
            val (expired, kept) = RecycleBin.partitionExpired(binState.value, now)
            expired.forEach { it.item.encryptedPath?.let { path -> File(path).delete() } }
            binState.value = kept
            persist()
            expired.size
        }

    override suspend fun openDecrypted(itemId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val item =
                itemsState.value.firstOrNull { it.id == itemId }
                    ?: binState.value.firstOrNull { it.item.id == itemId }?.item
                    ?: return@withContext null
            val path = item.encryptedPath ?: return@withContext null
            val blob = File(path)
            if (!blob.exists()) return@withContext null
            val out = ByteArrayOutputStream()
            blob.inputStream().use { source -> crypto.decrypt(source, out) }
            out.toByteArray()
        }

    // --- Persistence (org.json; app-private, plaintext metadata over encrypted blobs) ---

    private fun persist() {
        val root =
            JSONObject().apply {
                put("items", itemsState.value.toJsonArray { it.toJson() })
                put("folders", foldersState.value.toJsonArray { it.toJson() })
                put(
                    "bin",
                    binState.value.toJsonArray { entry ->
                        JSONObject().apply {
                            put("deletedAt", entry.deletedAt)
                            put("item", entry.item.toJson())
                        }
                    },
                )
            }
        indexFile.writeText(root.toString())
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        runCatching {
            val root = JSONObject(indexFile.readText())
            itemsState.value = root.optJSONArray("items").mapObjects { it.toVaultItem() }
            foldersState.value = root.optJSONArray("folders").mapObjects { it.toVaultFolder() }
            binState.value =
                root.optJSONArray("bin").mapObjects { obj ->
                    RecycleBinEntry(
                        item = obj.getJSONObject("item").toVaultItem(),
                        deletedAt = obj.getLong("deletedAt"),
                    )
                }
        }
    }

    private fun VaultItem.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("category", category.name)
            put("originalName", originalName)
            put("dateLabel", dateLabel)
            put("sortKey", sortKey)
            put("folderId", folderId ?: JSONObject.NULL)
            put("encryptedPath", encryptedPath ?: JSONObject.NULL)
            put("sizeBytes", sizeBytes)
            put("durationMs", durationMs)
            put("mimeType", mimeType ?: JSONObject.NULL)
        }

    private fun JSONObject.toVaultItem(): VaultItem =
        VaultItem(
            id = getString("id"),
            category = VaultCategory.valueOf(getString("category")),
            originalName = getString("originalName"),
            dateLabel = getString("dateLabel"),
            sortKey = getLong("sortKey"),
            folderId = optNullableString("folderId"),
            encryptedPath = optNullableString("encryptedPath"),
            sizeBytes = optLong("sizeBytes", 0L),
            durationMs = optLong("durationMs", 0L),
            mimeType = optNullableString("mimeType"),
        )

    private fun VaultFolder.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("category", category.name)
            put("name", name)
            put("itemCount", itemCount)
        }

    private fun JSONObject.toVaultFolder(): VaultFolder =
        VaultFolder(
            id = getString("id"),
            category = VaultCategory.valueOf(getString("category")),
            name = getString("name"),
            itemCount = optInt("itemCount", 0),
        )

    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key, null)

    private fun <T> List<T>.toJsonArray(transform: (T) -> JSONObject): JSONArray =
        JSONArray().also { arr -> forEach { arr.put(transform(it)) } }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { transform(getJSONObject(it)) }
    }
}
