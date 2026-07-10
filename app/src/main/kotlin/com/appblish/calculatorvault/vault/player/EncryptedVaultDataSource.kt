package com.appblish.calculatorvault.vault.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.math.min

/**
 * A random-access [androidx.media3.datasource.DataSource] over an AES-256-GCM vault blob
 * (APP-347 / W1-DESIGN). It is a thin Media3 adapter over [VaultBlobReader], which holds
 * the (pure-JVM, unit-tested) seek engine: ExoPlayer receives **decrypted plaintext,
 * byte-for-byte identical to the original video**, with only the one 512 KiB chunk each
 * read/seek needs decrypted — never the whole file, and **never a plaintext temp file on
 * disk** (spec §1.1). This replaces Phase-1's whole-file `decryptToFile()` →
 * `Uri.fromFile()` path for video.
 *
 * ExoPlayer seeks by closing this source and re-[open]ing it at the byte offset its
 * extractor computed from the container's own seek table; because [VaultBlobReader] is
 * byte-transparent, every ExoPlayer extractor/format works unchanged and each seek costs a
 * single chunk decrypt (O(1) on the v2 format). Legacy v1 blobs fall back to forward-only
 * reads (still zero-plaintext-on-disk — spec §5).
 *
 * ### Security
 * Decrypt output goes only into process-private heap buffers handed straight to ExoPlayer;
 * nothing is written to cache/files/external storage. Every served chunk is GCM-verified,
 * so tamper / wrong key / truncation throw an [IOException] *before* a byte is delivered
 * (ExoPlayer surfaces it via `onPlayerError`, never a crash). The key stays inside
 * [VaultBlobReader]'s [com.appblish.calculatorvault.vault.crypto.VaultCrypto] — no key
 * material crosses the [DataSpec] URI, and no decrypted byte is ever logged.
 *
 * One instance per playback open; not shared across items or threads. All calls arrive on
 * ExoPlayer's `Loader` thread, never the UI thread (spec §1.3).
 */
@UnstableApi
class EncryptedVaultDataSource(
    private val source: VaultPlaybackSource,
    // isNetwork = false: a local file source, so ExoPlayer skips network-only bookkeeping.
) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var reader: VaultBlobReader? = null
    private var bytesRemaining: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        if (!source.blob.isFile) throw IOException("Vault blob missing: ${source.blob.name}")

        val r =
            try {
                VaultBlobReader(source.blob, source.crypto).also {
                    if (dataSpec.position > it.contentLength) throw IOException("Seek beyond end of media")
                    it.seekTo(dataSpec.position)
                }
            } catch (e: GeneralSecurityException) {
                throw IOException("Vault chunk auth failed on open", e)
            }
        reader = r

        val available = r.contentLength - dataSpec.position
        bytesRemaining =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else min(dataSpec.length, available)
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
        val r = reader ?: throw IOException("read() before open()")
        val toRead = min(length.toLong(), bytesRemaining).toInt()
        val n =
            try {
                r.read(buffer, offset, toRead)
            } catch (e: GeneralSecurityException) {
                // Per-chunk GCM auth failure (tamper / wrong key / truncation): surface as
                // an IOException so ExoPlayer raises onPlayerError rather than rendering
                // unauthenticated plaintext.
                throw IOException("Vault chunk auth failed", e)
            }
        if (n == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { reader?.close() }
        reader = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /**
     * Binds an [EncryptedVaultDataSource] to an already-resolved [VaultPlaybackSource]
     * (blob file + session cipher, resolved off-main by the ViewModel). The opaque
     * `vault://item/<id>` [androidx.media3.common.MediaItem] URI carries **no** file path or
     * key — resolution happened before this factory was built, keeping key material off the
     * URI (spec §1.1, W1 security condition #4).
     */
    @UnstableApi
    class Factory(
        private val source: VaultPlaybackSource,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedVaultDataSource(source)
    }
}
