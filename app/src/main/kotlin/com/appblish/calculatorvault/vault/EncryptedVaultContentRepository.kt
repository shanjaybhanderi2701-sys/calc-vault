package com.appblish.calculatorvault.vault

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.appblish.calculatorvault.vault.media.BulkOpProgress
import com.appblish.calculatorvault.vault.media.BulkOpService
import com.appblish.calculatorvault.vault.media.MediaSink
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.media.VaultThumbnails
import com.appblish.calculatorvault.vault.model.DefaultVaultFolders
import com.appblish.calculatorvault.vault.model.RecycleBin
import com.appblish.calculatorvault.vault.model.RecycleBinEntry
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.UnhideDisposition
import com.appblish.calculatorvault.vault.model.UnhideOutcome
import com.appblish.calculatorvault.vault.model.UnhideResult
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
import java.security.SecureRandom
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
 * Removal of the *public* original: under **All Files Access** [hide] deletes it directly
 * (no consent dialog, board directive APP-248) and returns the stored item with a null
 * [VaultItem.sourceUri]. Only when a direct delete is not possible (pre-R / unknown path)
 * does the returned item keep its [VaultItem.sourceUri] so the hide/import UI can complete
 * removal via a MediaStore delete-request; the persisted copies never retain it.
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

    // Cause of the most recent failed key derivation, kept for the on-device hide
    // diagnostic (APP-248) so a locked-at-hide-time failure is not silent.
    @Volatile
    private var lastUnlockError: String? = null

    private val unlockScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val itemsState = MutableStateFlow<List<VaultItem>>(emptyList())

    // Starts at the seed catalog (not empty) so the home dual-counts and the category
    // roots agree from the very first render — a fresh install must never read "0 Folders"
    // while Photos already shows the seeded Download folder (APP-234 spec §1.3). The ids
    // are the stable seed ids, so [loadIndex] replacing this with the persisted truth is
    // invisible on a fresh vault and only corrects folders the user renamed/deleted.
    private val foldersState = MutableStateFlow(DefaultVaultFolders.forFreshVault())
    private val binState = MutableStateFlow<List<RecycleBinEntry>>(emptyList())

    /** True once [unlock] has derived the data key and loaded the index (test/UI probe). */
    fun isUnlocked(): Boolean = crypto != null

    override fun unlock() {
        // Fire-and-forget eager priming for the UI: derive the key on the IO scope so the
        // home/category surfaces are ready by the time the user browses. Write paths that
        // must not race this (see [hide]) call [awaitUnlocked] instead and suspend on it.
        unlockScope.launch { awaitUnlocked() }
    }

    /**
     * Ensure the data key is derived and the index loaded, deriving them **inline** (and
     * suspending until ready) if the vault is not yet unlocked. Returns the live
     * [VaultCrypto], or null when the vault genuinely cannot be unlocked yet — no session
     * passphrase or All Files Access not granted, in which case the key file/index in
     * public storage are unreadable and there is nothing to unlock into.
     *
     * Unlike [unlock] (fire-and-forget), a caller that `await`s this is guaranteed a
     * populated cipher on return. This closes the APP-248 defect the board hit: a hide
     * kicked off moments after the All-Files-Access grant returned — while the eager
     * [unlock] coroutine was still burning PBKDF2 — ran with `crypto` still null, so every
     * item fell into [encryptSource]'s catch and was reported as "N failed" with nothing
     * actually hidden.
     */
    private suspend fun awaitUnlocked(): VaultCrypto? {
        crypto?.let { return it }
        val passphrase = VaultSession.passphrase ?: return null
        // The key file + index live in public storage; without All Files Access we cannot
        // even read them. Stay locked (empty vault) until the primer grants access.
        if (!StoragePermissions.hasAllFilesAccess(appContext)) return null
        return mutex.withLock {
            crypto?.let { return@withLock it }
            val dataKey =
                try {
                    VaultKeyFile(VaultStorage.keyFile(appContext)).unlockOrCreate(passphrase)
                } catch (e: Exception) {
                    // Wrong passphrase / unreadable key file: leave the vault locked. Keep the
                    // cause for the on-device hide diagnostic (APP-248).
                    lastUnlockError = "${e.javaClass.simpleName}: ${e.message}"
                    android.util.Log.w("VaultUnlock", "unlock failed: ${e.javaClass.simpleName}: ${e.message}")
                    return@withLock null
                }
            val cipher = VaultCrypto(dataKey)
            // Load BEFORE publishing the key: isUnlocked() promises "derived the data
            // key AND loaded the index", and observers that gate on it (tests, UI
            // probes) must never catch the pre-load placeholder state (APP-244 race:
            // the IO thread is preemption-prone right after the PBKDF2 burn).
            loadIndex(cipher)
            crypto = cipher
            cipher
        }
    }

    /**
     * Human-readable reason the vault could not be unlocked at hide time, for the on-device
     * diagnostic (APP-248): distinguishes a missing session, missing All Files Access, and a
     * key-derivation error so a board user can report exactly why "0 hidden, N failed".
     */
    private fun lockDiagnostic(): String =
        when {
            VaultSession.passphrase == null -> "vault locked (no session)"
            !StoragePermissions.hasAllFilesAccess(appContext) -> "no all-files access"
            else -> lastUnlockError?.let { "unlock error: $it" } ?: "vault locked"
        }

    override fun lock() {
        // Drop the data key and every cached item so a subsequent [unlock] re-derives from
        // the current session passphrase / namespace. This is the decoy-isolation seam:
        // switching real ↔ decoy calls lock() before VaultSession.begin(), so the shared
        // singleton never serves one vault's content under another vault's passphrase.
        crypto = null
        itemsState.value = emptyList()
        foldersState.value = DefaultVaultFolders.forFreshVault()
        binState.value = emptyList()
        // Locking must also drop every decoded thumbnail pixel from memory (APP-244):
        // the LRU is exactly as sensitive as the decrypted content it was decoded from.
        VaultThumbnailPipeline.clear()
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
            // Fresh batch: drop any diagnostic reason from a previous run (APP-248).
            BulkOpProgress.clearFailure()
            // Derive the data key *before* encrypting. hide() can be reached moments after
            // the All-Files-Access grant returns, while the eager unlock() is still burning
            // PBKDF2 on another thread (APP-248): without this await the cipher would still
            // be null and every item would be caught and reported as "failed". A null here
            // means the vault genuinely cannot unlock (no session / no permission) — there
            // is nowhere to hide into, so return an empty result rather than a phantom batch.
            val cipher =
                awaitUnlocked() ?: run {
                    // Surface WHY the vault could not unlock so a board user can report it
                    // (the failure is otherwise silent — see BulkOpProgress.lastFailureReason).
                    BulkOpProgress.reportFailure(lockDiagnostic())
                    return@withContext emptyList()
                }
            withBulkOp("Hiding files", items.size) {
                val stored = mutableListOf<VaultItem>()
                for ((index, staged) in items.withIndex()) {
                    // UUID file name, extension stripped: nothing about the original leaks from
                    // the blob's name. Stored as a bare name so the vault dir stays relocatable.
                    val blobName = UUID.randomUUID().toString()
                    val blob = VaultStorage.blobFile(appContext, blobName)
                    val encryptedBytes =
                        try {
                            encryptSource(staged, blob, cipher)
                        } catch (e: Exception) {
                            // Skip an unreadable source rather than aborting the whole batch;
                            // the original is left in place (nothing to un-hide). Record the
                            // first failure's cause for the on-device diagnostic (APP-248).
                            BulkOpProgress.reportFailure("${e.javaClass.simpleName}: ${e.message}")
                            android.util.Log.w("VaultHide", "encrypt failed for ${staged.originalName}", e)
                            blob.delete()
                            BulkOpProgress.update(index + 1)
                            continue
                        }
                    // Capture resolution + modified date from the original for the Property
                    // dialog (spec §1.3 — Property reads the index, never the blob). Best-effort:
                    // a source that won't decode simply leaves the fields at 0 ("—" in the UI).
                    val meta = captureMetadata(staged)
                    val item =
                        staged.copy(
                            id = "v$blobName",
                            encryptedPath = blobName,
                            sizeBytes = encryptedBytes,
                            sourceUri = null,
                            widthPx = meta.widthPx,
                            heightPx = meta.heightPx,
                            dateModifiedMs = meta.dateModifiedMs,
                        )
                    // Hide-time thumbnail (APP-244): render a ~200px JPEG from the still-
                    // readable public source and persist it *encrypted* next to the blob,
                    // so the grid never has to decrypt the full item for a tile. Strictly
                    // best-effort — a preview failure must never fail the hide.
                    runCatching {
                        VaultThumbnails.sourceThumbJpeg(appContext, staged)?.let { jpeg ->
                            writeThumb(blobName, jpeg)
                        }
                    }
                    mutex.withLock {
                        itemsState.value = itemsState.value + item
                        persist()
                    }
                    // Board directive (APP-248): with All Files Access, remove the public
                    // original right here via a direct file delete — no MediaStore consent
                    // dialog. Only when that is not possible do we hand the Uri back so the
                    // UI can run the scoped-storage delete-request as a fallback.
                    val removed = deleteOriginalDirectly(staged)
                    stored += item.copy(sourceUri = if (removed) null else staged.sourceUri)
                    BulkOpProgress.update(index + 1)
                }
                stored
            }
        }

    /** Stream the staged original through [cipher] into [blob]; returns blob size. */
    private fun encryptSource(
        staged: VaultItem,
        blob: File,
        cipher: VaultCrypto,
    ): Long {
        openSource(staged).use { source ->
            blob.outputStream().use { sink -> cipher.encrypt(source, sink) }
        }
        return blob.length()
    }

    /**
     * Open the staged original's bytes. **All Files Access first** (board directive APP-248):
     * when we hold `MANAGE_EXTERNAL_STORAGE` and know the original's public path, read the
     * file **directly** via [java.io.File] rather than `openInputStream` on its MediaStore
     * `content://` Uri. On several devices/OEMs that Uri read is refused when the app holds
     * only All Files Access and no `READ_MEDIA_*` runtime grant (we dropped those, APP-219) —
     * which threw for every item and surfaced as "0 hidden, N failed". The direct file path
     * needs only All Files Access, which the picker already required to list these items.
     * Falls back to the content Uri (then to the name bytes for synthetic sources).
     */
    private fun openSource(staged: VaultItem): java.io.InputStream {
        originalFile(staged)?.let { file ->
            if (file.isFile) {
                return file.inputStream()
            }
        }
        val uri = staged.sourceUri
        return if (uri != null) {
            resolver.openInputStream(Uri.parse(uri)) ?: error("Cannot open source $uri")
        } else {
            // No public source (e.g. a synthesized contact vCard passed as name bytes):
            // encrypt the original name so the blob is still a real ciphertext.
            staged.originalName.toByteArray().inputStream()
        }
    }

    /**
     * Resolve the staged original to its public-storage [File] from its
     * [VaultItem.relativePath] (e.g. "DCIM/Camera/") + [VaultItem.originalName], or null when
     * we lack All Files Access or the path is unknown (sample/preview/contacts rows). Same
     * external-storage root MediaSink already uses for legacy write-back.
     */
    private fun originalFile(staged: VaultItem): File? {
        if (!StoragePermissions.hasAllFilesAccess(appContext)) return null
        val rel = staged.relativePath
            ?.trim()
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() } ?: return null
        val name = staged.originalName.takeIf { it.isNotBlank() } ?: return null
        return File(File(Environment.getExternalStorageDirectory(), rel), name)
    }

    /**
     * Remove the public original after it has been vaulted. **All Files Access path** deletes
     * the file directly (and clears its now-stale MediaStore row) with **no** per-file
     * consent dialog — that `MediaStore.createDeleteRequest` dialog is the scoped-storage
     * fallback for apps *without* broad access, which the board flagged as not our flow
     * (APP-248). Returns true when the original is gone; false means the caller must fall back
     * to the UI's delete-request path (e.g. pre-R, or path unknown).
     */
    private fun deleteOriginalDirectly(staged: VaultItem): Boolean {
        if (!StoragePermissions.hasAllFilesAccess(appContext)) return false
        // Primary: resolver.delete on the item's own MediaStore Uri. Under All Files Access
        // this removes both the row AND the underlying file with no RecoverableSecurity
        // Exception / consent dialog, and uses MediaStore's authoritative path (more reliable
        // than a reconstructed one). Return true only when it actually removed a row.
        staged.sourceUri?.let { uri ->
            val rows = runCatching { resolver.delete(Uri.parse(uri), null, null) }.getOrDefault(0)
            if (rows > 0) return true
        }
        // Fallback: delete the reconstructed public File directly. Returns true only when a
        // real file was present and removed — never a phantom "gone" for a missing path, so a
        // still-present original correctly falls back to the UI delete-request.
        return originalFile(staged)?.let { runCatching { it.isFile && it.delete() }.getOrDefault(false) } ?: false
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

    override suspend fun unhide(itemIds: Set<String>): Int = unhideTo(itemIds, UnhideDestination.Original).unhidden

    override suspend fun unhideTo(
        itemIds: Set<String>,
        destination: UnhideDestination,
    ): UnhideResult =
        withContext(Dispatchers.IO) {
            val cipher = crypto ?: return@withContext UnhideResult()
            val targets = itemsState.value.filter { it.id in itemIds }
            val outcomes = mutableListOf<UnhideOutcome>()
            for (item in targets) {
                // Blobs are stored as bare UUID names under the current .CalcVault/ dir;
                // resolveBlob handles both those and any legacy absolute paths.
                val blob = item.encryptedPath?.let(::resolveBlob)
                if (blob == null || !blob.exists()) {
                    outcomes += UnhideOutcome(item.id, UnhideDisposition.FAILED)
                    continue
                }
                // Stream blob → decrypt → public storage without materializing the
                // plaintext in memory (a vault video can be larger than the heap). Only on
                // a confirmed write-back do we drop the vault copy — a failed decrypt or
                // write leaves the encrypted original in place (never lose it), and
                // MediaSink discards any partially written plaintext.
                val result =
                    mediaSink.writeBack(item, destination) { out ->
                        blob.inputStream().use { source -> cipher.decrypt(source, out) }
                    }
                if (result.uri == null) {
                    outcomes += UnhideOutcome(item.id, UnhideDisposition.FAILED)
                    continue
                }
                // Securely wipe then unlink — the decrypted copy now lives in the gallery,
                // so the vault blob must leave no recoverable ciphertext behind. Also evict
                // the encrypted thumb cache: the item is no longer hidden (APP-244).
                secureWipe(blob)
                dropThumb(item)
                mutex.withLock {
                    itemsState.value = itemsState.value.filterNot { it.id == item.id }
                    persist()
                }
                outcomes += UnhideOutcome(item.id, result.disposition, result.destinationLabel)
            }
            UnhideResult(outcomes)
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
            removed.forEach {
                it.item.encryptedPath?.let { path -> secureWipe(resolveBlob(path)) }
                dropThumb(it.item)
            }
            binState.value = kept
            persist()
        }

    override suspend fun permanentlyDelete(itemIds: Set<String>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Secure blob wipe + index-entry removal, straight from the vault (the
                // 2-step-confirmed permanent path, spec §1.5). Never routes through the bin.
                val (removed, kept) = itemsState.value.partition { it.id in itemIds }
                removed.forEach { it.encryptedPath?.let { path -> secureWipe(resolveBlob(path)) } }
                itemsState.value = kept
                persist()
            }
        }

    override suspend fun purgeExpired(now: Long): Int =
        mutex.withLock {
            val (expired, kept) = RecycleBin.partitionExpired(binState.value, now)
            expired.forEach {
                it.item.encryptedPath?.let { path -> secureWipe(resolveBlob(path)) }
                dropThumb(it.item)
            }
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

    override suspend fun decryptToFile(
        itemId: String,
        dest: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val cipher = crypto ?: return@withContext false
            val item =
                itemsState.value.firstOrNull { it.id == itemId }
                    ?: binState.value.firstOrNull { it.item.id == itemId }?.item
                    ?: return@withContext false
            val blob = item.encryptedPath?.let(::resolveBlob) ?: return@withContext false
            if (!blob.exists()) return@withContext false
            try {
                dest.outputStream().use { out ->
                    blob.inputStream().use { source -> cipher.decrypt(source, out) }
                }
                true
            } catch (e: Exception) {
                // GCM tag failure / IO error: drop the partial file so a caller never
                // renders unauthenticated plaintext.
                dest.delete()
                false
            }
        }

    // --- Encrypted stored thumbnails (APP-244) ----------------------------------------

    override suspend fun openThumbnail(itemId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val cipher = crypto ?: return@withContext null
            val blobName = findItem(itemId)?.let(::thumbName) ?: return@withContext null
            val file = VaultStorage.thumbFile(appContext, blobName)
            if (!file.exists()) return@withContext null
            try {
                val out = ByteArrayOutputStream()
                file.inputStream().use { source -> cipher.decrypt(source, out) }
                out.toByteArray()
            } catch (e: Exception) {
                // A thumb that fails its tag is worthless and would fail forever — drop it
                // so the pipeline regenerates a good one from the (independently
                // authenticated) blob.
                file.delete()
                null
            }
        }

    override suspend fun saveThumbnail(
        itemId: String,
        jpegBytes: ByteArray,
    ) {
        withContext(Dispatchers.IO) {
            val blobName = findItem(itemId)?.let(::thumbName) ?: return@withContext
            runCatching { writeThumb(blobName, jpegBytes) }
        }
    }

    /** Encrypt [jpeg] into the `thumbs/` file for [blobName] — never plaintext on disk. */
    private fun writeThumb(
        blobName: String,
        jpeg: ByteArray,
    ) {
        val cipher = crypto ?: return
        val file = VaultStorage.thumbFile(appContext, blobName)
        try {
            file.outputStream().use { out -> jpeg.inputStream().use { cipher.encrypt(it, out) } }
        } catch (e: Exception) {
            file.delete()
        }
    }

    /** Delete [item]'s stored thumb and evict its decoded tile (delete/restore, APP-244). */
    private fun dropThumb(item: VaultItem) {
        item.encryptedPath?.let { path ->
            runCatching { VaultStorage.thumbFile(appContext, thumbNameOf(path)).delete() }
        }
        VaultThumbnailPipeline.evict(item.id)
    }

    private fun findItem(itemId: String): VaultItem? =
        itemsState.value.firstOrNull { it.id == itemId }
            ?: binState.value.firstOrNull { it.item.id == itemId }?.item

    /** Thumb file name for an item: its blob's bare name (legacy absolute paths reduce). */
    private fun thumbName(item: VaultItem): String? = item.encryptedPath?.let(::thumbNameOf)

    private fun thumbNameOf(encryptedPath: String): String = encryptedPath.substringAfterLast('/')

    // --- Storage helpers ------------------------------------------------------------

    /**
     * Run [block] as a tracked bulk operation (spec §11): publish per-item progress to
     * [BulkOpProgress] and keep the process alive under [BulkOpService]'s foreground
     * "Processing N of M" notification so the OS never kills a half-finished batch. The
     * service is best-effort — if it cannot start (background restrictions, denied
     * POST_NOTIFICATIONS) the batch still runs to completion here on Dispatchers.IO; only
     * the progress UI is lost. `finally` guarantees the tracker (and thus the service)
     * shuts down even when the batch throws.
     */
    private inline fun <T> withBulkOp(
        label: String,
        total: Int,
        block: () -> T,
    ): T {
        BulkOpProgress.start(label, total)
        BulkOpService.start(appContext)
        return try {
            block()
        } finally {
            BulkOpProgress.finish()
        }
    }

    /**
     * Securely erase [blob]: overwrite its bytes with random data (flushed to disk) before
     * unlinking, so a permanent-delete leaves no recoverable ciphertext on the media —
     * spec §1.5. A plain `delete()` only drops the directory entry. Best-effort on flash
     * (wear-levelling can retain pages); it still denies trivial file-carving recovery and
     * is the strongest guarantee available from userspace. Falls back to `delete()` if the
     * overwrite throws.
     */
    private fun secureWipe(blob: File) {
        if (!blob.exists()) return
        try {
            val length = blob.length()
            if (length > 0) {
                val random = SecureRandom()
                val chunk = ByteArray(64 * 1024)
                blob.outputStream().use { out ->
                    var remaining = length
                    while (remaining > 0) {
                        val n = minOf(remaining, chunk.size.toLong()).toInt()
                        random.nextBytes(chunk)
                        out.write(chunk, 0, n)
                        remaining -= n
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VaultWipe", "secure overwrite failed: ${e.javaClass.simpleName}")
        } finally {
            blob.delete()
        }
    }

    private data class SourceMetadata(
        val widthPx: Int = 0,
        val heightPx: Int = 0,
        val dateModifiedMs: Long = 0L,
    )

    /**
     * Best-effort resolution + modified-date read from a staged item's public source, so the
     * Property dialog can report them from the index. Photos: decode bounds only (no full
     * bitmap in memory). Date: MediaStore DATE_MODIFIED. Any failure yields zeros → "—".
     */
    private fun captureMetadata(staged: VaultItem): SourceMetadata {
        val uri = staged.sourceUri?.let(Uri::parse) ?: return SourceMetadata()
        var width = 0
        var height = 0
        if (staged.category == VaultCategory.PHOTOS) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                width = opts.outWidth.coerceAtLeast(0)
                height = opts.outHeight.coerceAtLeast(0)
            }
        }
        val modified =
            runCatching {
                resolver
                    .query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
                    ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) * 1000L else 0L }
                    ?: 0L
            }.getOrDefault(0L)
        return SourceMetadata(width, height, modified)
    }

    /**
     * Resolve a stored blob reference to a file. New items store a bare UUID name resolved
     * against the current public vault dir; a legacy absolute path (older installs) is used
     * as-is so pre-existing blobs still open.
     */
    private fun resolveBlob(encryptedPath: String): File =
        if (encryptedPath.contains('/')) File(encryptedPath) else VaultStorage.blobFile(appContext, encryptedPath)

    // --- Persistence: AES-256-GCM encrypted index in the public .CalcVault/ folder ---

    private fun persist(cipher: VaultCrypto? = crypto) {
        if (cipher == null) return
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

    /**
     * Load the encrypted index (when present), then top up the predefined default folders.
     *
     * Seeding is **idempotent and category-scoped**, not first-run-only (APP-225 board fix):
     * the public `.CalcVault/` folder survives uninstall by design, so an index written by an
     * older build (pre-"Download" catalog, or the legacy Camera/Screenshots/… seed set) is a
     * perfectly normal sight here — and the old exists()-gated seed never fired for it. After
     * every successful load, each Phase-1 category with ZERO folders gets its default(s) from
     * [DefaultVaultFolders]; a category holding ≥1 folder is left alone, so a "Download" the
     * user deleted stays deleted while other folders remain in that category. Tradeoff:
     * deleting a category's *last* folder resurrects "Download" on the next unlock — accepted
     * to guarantee migrated/stale vaults always open with a usable default. Items/folders in
     * non-Phase-1 categories (e.g. FILES/CONTACTS from old builds) load and persist untouched;
     * they are simply not shown by Phase-1 UI. Each namespace (real vault + every decoy) still
     * seeds only its OWN index.enc under its own directory, so decoy isolation is unchanged.
     */
    private fun loadIndex(cipher: VaultCrypto) {
        val file = VaultStorage.indexFile(appContext)
        if (file.exists()) {
            val loaded =
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
            // An index we cannot decode is left exactly as found — never clobber it with a
            // freshly seeded one (its blobs may still be recoverable out-of-band).
            if (loaded.isFailure) {
                val e = loaded.exceptionOrNull()
                android.util.Log.w("VaultUnlock", "index decode failed: ${e?.javaClass?.simpleName}: ${e?.message}")
                return
            }
        }
        val missing = DefaultVaultFolders.missingDefaults(foldersState.value)
        if (missing.isNotEmpty()) {
            foldersState.value = foldersState.value + missing
            persist(cipher)
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
            put("widthPx", widthPx)
            put("heightPx", heightPx)
            put("dateModifiedMs", dateModifiedMs)
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
            widthPx = optInt("widthPx", 0),
            heightPx = optInt("heightPx", 0),
            dateModifiedMs = optLong("dateModifiedMs", 0L),
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
