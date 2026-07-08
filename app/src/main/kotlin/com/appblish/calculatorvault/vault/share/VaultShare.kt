package com.appblish.calculatorvault.vault.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.appblish.calculatorvault.vault.VaultContentRepository
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import java.io.File
import java.util.UUID

/**
 * Vault-safe Share (APP-294, spec = the owner fix pass P2 item 8): the one action that
 * deliberately lets a decrypted copy leave the vault, so its whole design is the
 * temp-copy contract:
 *
 *  1. **Decrypt to a scoped temp copy, never expose the vault.** Each shared item is
 *     streamed (blob → chunked AES-GCM cipher → file, the APP-244 bounded-memory path via
 *     [VaultContentRepository.decryptToFile]) into a fresh random session directory under
 *     `cacheDir/share/` — app-private storage no other app can read directly.
 *  2. **Serve the copy through a FileProvider only.** Receivers get an opaque
 *     `content://<pkg>.share/…` URI with a read-only grant. The provider's path config
 *     ([R.xml.vault_share_paths]) whitelists exactly the `share/` cache subtree, so the
 *     URI can never resolve the encrypted blobs, the index, or the public `.CalcVault/`
 *     folder — the vault's real storage path and ciphertext are unreachable by design.
 *  3. **Delete the temp copy as soon as the share ends.** The launching screen invokes
 *     [purge] from its activity-result callback — the moment the chooser/receiver flow
 *     returns control, covering completed *and* cancelled shares. [purgeAll] runs at
 *     process start ([com.appblish.calculatorvault.CalculatorVaultApp]) so a copy
 *     stranded by a crash or force-kill never outlives one app session.
 *
 * A failed decrypt aborts the whole session (partial dir deleted, null returned) — the
 * caller shows an honest "Couldn't share." instead of silently sharing fewer items.
 */
object VaultShare {
    /** One prepared share: the session's temp dir, its provider URIs, and the send MIME. */
    data class Session(
        val dir: File,
        val uris: List<Uri>,
        val mimeType: String,
    )

    /**
     * Decrypt [items] into a new share session under [Context.getCacheDir]`/share/`.
     * Streams each blob in chunk-bounded memory (a large video never materializes on the
     * heap) on the caller's dispatcher — callers invoke this off the main thread. Returns
     * null (and leaves no files behind) if the vault is locked or any item fails.
     */
    suspend fun prepare(
        context: Context,
        repository: VaultContentRepository,
        items: List<VaultItem>,
    ): Session? {
        if (items.isEmpty()) return null
        val sessionDir = File(shareRoot(context), UUID.randomUUID().toString())
        if (!sessionDir.mkdirs()) return null
        val names = displayNames(items.map { it.originalName })
        val uris = ArrayList<Uri>(items.size)
        items.forEachIndexed { index, item ->
            val copy = File(sessionDir, names[index])
            val ok = runCatching { repository.decryptToFile(item.id, copy) }.getOrDefault(false)
            if (!ok) {
                // All-or-nothing: never silently share a subset (fix-pass "never fail
                // silently" bar); the partial session is removed before reporting failure.
                sessionDir.deleteRecursively()
                return null
            }
            uris += FileProvider.getUriForFile(context, authority(context), copy)
        }
        return Session(dir = sessionDir, uris = uris, mimeType = shareMimeType(items))
    }

    /**
     * The system share sheet for [session]: `ACTION_SEND` (one item) or
     * `ACTION_SEND_MULTIPLE`, carrying only the provider URIs with a **read-only** grant
     * (never write — a receiver must not be able to alter the temp copy, and the grant
     * dies with the URI when [purge] deletes the file).
     */
    fun chooserIntent(session: Session): Intent {
        val send =
            if (session.uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, session.uris.single()) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(session.uris))
                }
            }
        send.type = session.mimeType
        // ClipData mirrors the extras so the grant follows the intent through the chooser
        // on every API level (extras alone don't propagate grants pre-ClipData routing).
        send.clipData =
            ClipData(
                "vault share",
                arrayOf(session.mimeType),
                ClipData.Item(session.uris.first()),
            ).apply { session.uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Delete [session]'s temp copies now (share completed or cancelled). */
    fun purge(session: Session) {
        session.dir.deleteRecursively()
    }

    /** Process-restart backstop: wipe every share session (crash/force-kill leftovers). */
    fun purgeAll(context: Context) {
        shareRoot(context).deleteRecursively()
    }

    /** The whitelisted share subtree — must match `res/xml/vault_share_paths.xml`. */
    private fun shareRoot(context: Context): File = File(context.cacheDir, SHARE_DIR)

    /** Derived from the package so debug/release applicationId variants can't collide. */
    private fun authority(context: Context): String = "${context.packageName}.share"

    /**
     * Receiver-facing file names: the item's own [VaultItem.originalName] (the vault's
     * UUID blob name must never leak), sanitized and de-duplicated within the session.
     */
    internal fun displayNames(originalNames: List<String>): List<String> {
        val used = HashSet<String>()
        return originalNames.map { raw ->
            val base = safeFileName(raw)
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.take(dot) else base
            val ext = if (dot > 0) base.substring(dot) else ""
            var candidate = base
            var attempt = 1
            while (!used.add(candidate.lowercase())) {
                attempt++
                candidate = "$stem ($attempt)$ext"
            }
            candidate
        }
    }

    /**
     * Neutralize a display name for use as a single cache file name: path separators and
     * traversal dots cannot escape the session dir (defense in depth — the FileProvider
     * path whitelist is the hard boundary), control characters are stripped, and an
     * empty/dot-only result falls back to a neutral name.
     */
    internal fun safeFileName(name: String): String {
        val cleaned =
            name
                .replace('\\', '_')
                .replace('/', '_')
                .replace(":", "_")
                .filter { it.code >= 32 }
                .trim()
                .trimStart('.')
                .take(MAX_NAME_LENGTH)
        return cleaned.ifBlank { "shared_item" }
    }

    /**
     * The send MIME: the single item's own type; for a batch, the common family wildcard
     * ("image" + slash-star) when every item agrees on the top-level type, else the fully
     * generic wildcard. Items without a stored type fall back to their category's family
     * so photo/video batches stay well-typed.
     */
    internal fun shareMimeType(items: List<VaultItem>): String {
        val types = items.map { it.mimeType ?: fallbackMime(it.category) }
        types.singleOrNull()?.let { return it }
        val families = types.map { it.substringBefore('/') }.toSet()
        return if (families.size == 1) "${families.single()}/*" else "*/*"
    }

    private fun fallbackMime(category: VaultCategory): String =
        when (category) {
            VaultCategory.PHOTOS -> "image/*"
            VaultCategory.VIDEOS -> "video/*"
            VaultCategory.AUDIOS -> "audio/*"
            else -> "application/octet-stream"
        }

    /** Must match the `path` attribute in `res/xml/vault_share_paths.xml`. */
    internal const val SHARE_DIR = "share"

    private const val MAX_NAME_LENGTH = 120
}
