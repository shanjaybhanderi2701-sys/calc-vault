package com.appblish.calculatorvault.vault.viewer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.appblish.calculatorvault.vault.crypto.DecryptingBlobReader
import java.io.IOException

/**
 * A Media3 [DataSource] that streams a vault video/audio blob **decrypted on demand**,
 * replacing Phase-1's whole-file `decryptToFile` temp-file path (APP-347 / spec §8).
 *
 * ExoPlayer drives this from its background loader thread (§1.3, never the UI thread):
 * [open] is called on the first load and again on every scrub/seek (ExoPlayer closes and
 * reopens with a new `dataSpec.position`); [read] pulls decrypted plaintext. All bytes come
 * from a [DecryptingBlobReader], so **no plaintext ever touches disk** (§1.1) and an
 * arbitrary-offset seek decrypts just one 512 KiB chunk (§1.2). Because the served byte
 * stream is identical to the original file, every ExoPlayer extractor/codec works unchanged.
 *
 * A missing item / key surfaces as an [IOException] → ExoPlayer's `onPlayerError`, which the
 * screen maps to a graceful message (§6) — never a crash.
 */
@UnstableApi
class EncryptedVaultDataSource(
    private val openReader: (itemId: String) -> DecryptingBlobReader?,
) : BaseDataSource(false) { // isNetwork = false (local vault blob)
    private var dataSpecUri: Uri? = null
    private var reader: DecryptingBlobReader? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        dataSpecUri = dataSpec.uri
        val itemId =
            dataSpec.uri.lastPathSegment
                ?: throw IOException("Vault media URI has no item id: ${dataSpec.uri}")
        val blobReader =
            openReader(itemId)
                ?: throw IOException("Vault item unavailable for playback: $itemId")
        reader = blobReader
        blobReader.seekTo(dataSpec.position)

        val length = blobReader.contentLength
        bytesRemaining =
            when {
                // Legacy v1: length unknown without a full decrypt — read until EOF.
                length < 0 -> C.LENGTH_UNSET.toLong()
                dataSpec.length == C.LENGTH_UNSET.toLong() -> length - dataSpec.position
                else -> minOf(dataSpec.length, length - dataSpec.position)
            }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val cap =
            if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                length
            } else {
                minOf(length.toLong(), bytesRemaining).toInt()
            }
        val n = reader?.read(buffer, offset, cap) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = dataSpecUri

    override fun close() {
        dataSpecUri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /**
     * Builds an [EncryptedVaultDataSource] bound to a repository [openReader] hook. Wired as
     * `ProgressiveMediaSource.Factory(EncryptedVaultDataSource.Factory(openReader))` over a
     * [vaultMediaUri]; the item id → blob/key resolution stays inside the hook, so no file
     * path or key material ever crosses the `MediaItem`/URI boundary.
     */
    @UnstableApi
    class Factory(
        private val openReader: (itemId: String) -> DecryptingBlobReader?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedVaultDataSource(openReader)
    }

    companion object {
        private const val SCHEME = "vault"

        /** Opaque `vault://item/<itemId>` uri — carries only the id, never a path or key. */
        fun vaultMediaUri(itemId: String): Uri =
            Uri
                .Builder()
                .scheme(SCHEME)
                .authority("item")
                .appendPath(itemId)
                .build()
    }
}
