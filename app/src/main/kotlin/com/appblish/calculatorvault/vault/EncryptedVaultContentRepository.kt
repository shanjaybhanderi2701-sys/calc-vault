package com.appblish.calculatorvault.vault

import android.content.Context
import android.net.Uri
import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.appblish.calculatorvault.vault.media.MediaSink
import com.appblish.calculatorvault.vault.model.RecycleBin
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultFolder
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Device [VaultContentRepository] that realizes the board's survive-uninstall vault
 * technique (vault-technique §1–2, APP-142). It streams each picked original through
 * [VaultCrypto] into an AES-256-GCM blob in the **hidden public** `.CalcVault/` dot-folder
 * ([VaultStorage]) — UUID file name, extension stripped — records metadata in an
 * **encrypted** index (`index.enc`) in the same folder, and serves decrypted bytes back to
 * the viewers.
 *
 * Because the whole vault lives on shared storage, it **survives app uninstall/reinstall**:
 * the OS wipes `filesDir` but leaves the public dot-folder. The data key that unlocks it
 * survives too — it is wrapped under a passphrase-derived key in `.vaultkey`
 * ([VaultKeyFile]), so re-entering the PIN after a reinstall re-derives the key and the
 * vault reads again. This replaces Phase 2's keystore-resident key (which the OS wiped on
 * uninstall) and its plaintext app-private index.
 *
 * [unlock] must run before content is readable: it needs both All Files Access
 * ([StoragePermissions]) and the session passphrase ([VaultSession]). It is a no-op until
 * both are present, so callers can invoke it eagerly and re-invoke it once the primer grants
 * access. All mutating flows mirror an in-memory snapshot persisted back to `index.enc`.
 *
 * Removal of the *public* original is completed by the hide/import UI via a MediaStore
 * delete-request (user-consented on API 30+), so [hide] returns the stored items still
 * carrying their [VaultItem.sourceUri]; the persisted copies never retain it.
 */
class EncryptedVaultContentRepository(
    context: Context,
) : VaultContentRepository {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val mediaSink = MediaSink(appContext)

    // The data key, available only after a successful [unlock]. Null == vault locked.
    @Volatile
    private var crypto: VaultCrypto? = null

    private val unlockScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val itemsState = MutableStateFlow<List<VaultItem>>(emptyList())
    private val foldersState = MutableStateFlow<List<VaultFolder>>(emptyList())
    private val binState = MutableStateFlow<List<RecycleBinEntry>>(emptyList())

    /** True once [unlock] has derived the data key and loaded the index (test/UI probe). */
    fun isUnlocked(): Boolean = crypto != null

    override fun unlock() {
        if (crypto != null) return
        val passphrase = VaultSession.passphrase ?: return
        // The key file + index live in public storage; without All Files Access we cannot
        // even read them. Stay locked (empty vault) until the primer grants access.
        if (!StoragePermissions.hasAllFilesAccess(appContext)) return
        unlockScope.launch {
            mutex.withLock {
                if (crypto != null) return@withLock
                val dataKey =
                    try {
                        VaultKeyFile(VaultStorage.keyFile(appContext)).unlockOrCreate(passphrase)
                    } catch (e: Exception) {
                        // Wrong passphrase / unreadable key file: leave the vault locked.
                        android.util.Log.w("VaultUnlock", "unlock failed: ${e.javaClass.simpleName}: ${e.message}")
                        return@withLock
                    }
                crypto = VaultCrypto(dataKey)
                loadIndex()
            }
        }
    }

    override fun lock() {
        // Drop the data key and every cached item so a subsequent [unlock] re-derives from
        // the current session passphrase / namespace. This is the decoy-isolation seam:
        // switching real ↔ decoy calls lock() before VaultSession.begin(), so the shared
        // singleton never serves one vault's content under another vault's passphrase.
        crypto = null
        itemsState.value = emptyList()
        foldersState.value = emptyList()
        binState.value = emptyList()
    }

    override fun items(category: VaultCategory): Flow<List<VaultItem>> =
        itemsState.map { all -> all.filter { it.category == category }.sortedByDescending { it.sortKey } }

    override fun allItems(): Flow<List<VaultItem>> = itemsState.map { all -> all.sortedByDescending { it.sortKey } }

    override fun folders(category: VaultCategory): Flow<List<VaultFolder>> =
        foldersState.map { all -> all.filter { it.category == category } }

    override fun categoryCounts(): Flow<Map<VaultCategory, Int>> =
        itemsState.map { all -> VaultCategory.entries.associateWith { cat -> all.count { it.category == cat } } }

    override fun folderCounts(): Flow<Map<VaultCategory, Int>> =
        foldersState.map { all -> VaultCategory.entries.associateWith { cat -> all.count { it.category == cat } } }

    override fun recent(limit: Int): Flow<List<VaultItem>> =
        itemsState.map { all -> all.sortedByDescending { it.sortKey }.take(limit) }

    override fun recycleBin(): Flow<List<RecycleBinEntry>> =
        binState.map { entries -> entries.sortedByDescending { it.deletedAt } }

    override suspend fun hide(items: List<VaultItem>): List<VaultItem> =
        withContext(Dispatchers.IO) {
            val stored = mutableListOf<VaultItem>()
            for (staged in items) {
                // UUID file name, extension stripped: nothing about the original leaks from
                // the blob's name. Stored as a bare name so the vault dir stays relocatable.
                val blobName = UUID.randomUUID().toString()
                val blob = VaultStorage.blobFile(appContext, blobName)
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
                        id = "v$blobName",
                        encryptedPath = blobName,
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
        val cipher = requireCrypto()
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
            blob.outputStream().use { sink -> cipher.encrypt(source, sink) }
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

    override suspend fun unhide(itemIds: Set<String>): Int =
        withContext(Dispatchers.IO) {
            val cipher = crypto ?: return@withContext 0
            val targets = itemsState.value.filter { it.id in itemIds }
            var restored = 0
            for (item in targets) {
                // Blobs are stored as bare UUID names under the current .CalcVault/ dir;
                // resolveBlob handles both those and any legacy absolute paths.
                val blob = item.encryptedPath?.let(::resolveBlob) ?: continue
                if (!blob.exists()) continue
                // Decrypt the blob back to cleartext bytes, then publish them to public
                // storage. Only on a confirmed write-back do we drop the vault copy — a
                // failed restore leaves the encrypted original in place.
                val plain =
                    try {
                        ByteArrayOutputStream()
                            .also { out -> blob.inputStream().use { source -> cipher.decrypt(source, out) } }
                            .toByteArray()
                    } catch (e: Exception) {
                        continue
                    }
                mediaSink.writeBack(item, plain) ?: continue
                blob.delete()
                mutex.withLock {
                    itemsState.value = itemsState.value.filterNot { it.id == item.id }
                    persist()
                }
                restored++
            }
            restored
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
            removed.forEach { it.item.encryptedPath?.let { path -> resolveBlob(path).delete() } }
            binState.value = kept
            persist()
        }

    override suspend fun purgeExpired(now: Long): Int =
        mutex.withLock {
            val (expired, kept) = RecycleBin.partitionExpired(binState.value, now)
            expired.forEach { it.item.encryptedPath?.let { path -> resolveBlob(path).delete() } }
            binState.value = kept
            persist()
            expired.size
        }

    override suspend fun openDecrypted(itemId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val cipher = crypto ?: return@withContext null
            val item =
                itemsState.value.firstOrNull { it.id == itemId }
                    ?: binState.value.firstOrNull { it.item.id == itemId }?.item
                    ?: return@withContext null
            val blob = item.encryptedPath?.let(::resolveBlob) ?: return@withContext null
            if (!blob.exists()) return@withContext null
            val out = ByteArrayOutputStream()
            blob.inputStream().use { source -> cipher.decrypt(source, out) }
            out.toByteArray()
        }

    // --- Storage helpers ------------------------------------------------------------

    private fun requireCrypto(): VaultCrypto = crypto ?: error("Vault is locked; call unlock() first")

    /**
     * Resolve a stored blob reference to a file. New items store a bare UUID name resolved
     * against the current public vault dir; a legacy absolute path (older installs) is used
     * as-is so pre-existing blobs still open.
     */
    private fun resolveBlob(encryptedPath: String): File =
        if (encryptedPath.contains('/')) File(encryptedPath) else VaultStorage.blobFile(appContext, encryptedPath)

    // --- Persistence: AES-256-GCM encrypted index in the public .CalcVault/ folder ---

    private fun persist() {
        val cipher = crypto ?: return
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
        val encrypted = ByteArrayOutputStream()
        root.toString().toByteArray(Charsets.UTF_8).inputStream().use { source ->
            cipher.encrypt(source, encrypted)
        }
        VaultStorage.indexFile(appContext).writeBytes(encrypted.toByteArray())
    }

    private fun loadIndex() {
        val cipher = crypto ?: return
        val file = VaultStorage.indexFile(appContext)
        if (!file.exists()) return
        runCatching {
            val out = ByteArrayOutputStream()
            file.inputStream().use { source -> cipher.decrypt(source, out) }
            val root = JSONObject(String(out.toByteArray(), Charsets.UTF_8))
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
            put("relativePath", relativePath ?: JSONObject.NULL)
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
            relativePath = optNullableString("relativePath"),
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
