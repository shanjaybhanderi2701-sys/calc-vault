package com.appblish.calculatorvault.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.appblish.calculatorvault.vault.SourceItem
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes real grid/preview thumbnails for the vault, replacing the placeholder tiles.
 *
 * Two sources feed the grids:
 *  - **Hidden items** ([forItem]) — the bytes live only inside the encrypted blob, so the
 *    caller supplies a decryptor ([bytesProvider]); images are down-sampled in-memory and
 *    videos are decoded to a first frame via [MediaMetadataRetriever] over a short-lived
 *    cache copy. Audios/files/contacts have no meaningful pixel preview → null (icon tile).
 *  - **Public originals** ([forSource]) in the hide picker — decoded straight from
 *    MediaStore's own thumbnail cache (API 29+), which is cheap and never touches the blob.
 *
 * All decoding is best-effort: any failure returns null so the grid falls back to its
 * neutral placeholder rather than crashing.
 */
object VaultThumbnails {
    /** Target longest-edge for a grid tile; keeps memory bounded for large photos/videos. */
    private const val TARGET_PX = 256

    /** Decode a preview for a hidden [item] from its decrypted blob bytes. */
    suspend fun forItem(
        context: Context,
        item: VaultItem,
        bytesProvider: suspend () -> ByteArray?,
    ): ImageBitmap? {
        val isImage =
            item.category == VaultCategory.PHOTOS || item.mimeType?.startsWith("image/") == true
        val bytes =
            when {
                isImage || item.category == VaultCategory.VIDEOS -> bytesProvider() ?: return null
                else -> return null
            }
        return withContext(Dispatchers.IO) {
            if (isImage) {
                decodeSampledImage(bytes)?.asImageBitmap()
            } else {
                videoFrame(context, item.id, bytes)?.asImageBitmap()
            }
        }
    }

    /** Decode a preview for a public-storage [source] in the hide picker (no decryption). */
    suspend fun forSource(
        context: Context,
        source: SourceItem,
    ): ImageBitmap? {
        val raw = source.contentUri.takeIf { it.isNotBlank() } ?: return null
        val mime = source.mimeType
        val isMedia = mime == null || mime.startsWith("image/") || mime.startsWith("video/")
        if (!isMedia) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver
                    .loadThumbnail(Uri.parse(raw), Size(TARGET_PX, TARGET_PX), null)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    /** Down-sample [bytes] to ~[TARGET_PX] on the longest edge so the full image never loads. */
    private fun decodeSampledImage(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sampleSize(
        width: Int,
        height: Int,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfW >= TARGET_PX && halfH >= TARGET_PX) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return sample
    }

    /** Extract a representative video frame by writing the decrypted bytes to a temp file. */
    private fun videoFrame(
        context: Context,
        id: String,
        bytes: ByteArray,
    ): Bitmap? {
        val tmp = File(context.cacheDir, "thumb_$id.mp4")
        val retriever = MediaMetadataRetriever()
        return try {
            tmp.writeBytes(bytes)
            retriever.setDataSource(tmp.absolutePath)
            // Prefer the closest sync (key)frame — some encoders have no decodable frame at
            // t=0, so getFrameAtTime(0) returns null; the representative-frame call and a
            // 1-second sample are more robust fallbacks before giving up on a preview.
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
            tmp.delete()
        }
    }
}
