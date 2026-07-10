package com.appblish.calculatorvault.vault.viewer

import android.media.MediaDataSource
import com.appblish.calculatorvault.vault.crypto.DecryptingBlobReader

/**
 * An [android.media.MediaDataSource] view over a decrypting vault [DecryptingBlobReader],
 * so [android.media.MediaMetadataRetriever] can pull a video's first frame for the viewer
 * preview **without any plaintext temp file** (APP-347 §1.1) — the same
 * decrypt-on-demand path the player uses. Only meaningful for the seekable v2 reader
 * (random `readAt`); a legacy/unknown-length blob reports size -1 and simply yields no
 * preview frame (the play button over the canvas is the whole preview).
 *
 * Not thread-safe: the retriever drives it from a single background thread; the owner
 * closes it after use.
 */
class VaultMediaDataSource(
    private val reader: DecryptingBlobReader,
) : MediaDataSource() {
    override fun getSize(): Long = reader.contentLength

    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (size == 0) return 0
        val length = reader.contentLength
        if (length in 0..position) return -1 // at/after EOF
        reader.seekTo(position)
        var read = 0
        while (read < size) {
            val n = reader.read(buffer, offset + read, size - read)
            if (n < 0) break
            read += n
        }
        return if (read == 0) -1 else read
    }

    override fun close() {
        reader.close()
    }
}
