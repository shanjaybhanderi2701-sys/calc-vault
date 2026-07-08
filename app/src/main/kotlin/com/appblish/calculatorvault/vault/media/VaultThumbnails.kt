package com.appblish.calculatorvault.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Decodes real grid/preview thumbnails for the vault, replacing the placeholder tiles.
 *
 * Three sources feed the grids:
 *  - **Stored encrypted thumbs** — the fast path (APP-244): a ~[STORED_THUMB_PX]px JPEG
 *    generated at hide-time ([sourceThumbJpeg]) and persisted *encrypted* next to the blob,
 *    decoded here via [decodeStoredThumb]. Orchestrated by [VaultThumbnailPipeline].
 *  - **Hidden items without a stored thumb** ([forItem], backfill for pre-APP-244 items) —
 *    the bytes live only inside the encrypted blob, so the caller supplies a decryptor
 *    ([bytesProvider]); images are down-sampled in-memory and videos are decoded to a first
 *    frame via [MediaMetadataRetriever] over a short-lived cache copy.
 *  - **Public originals** ([forSource]) in the hide picker — decoded straight from
 *    MediaStore's own thumbnail cache (API 29+), which is cheap and never touches the blob.
 *
 * All decoding is best-effort: any failure returns null so the grid falls back to its
 * neutral placeholder rather than crashing.
 */
object VaultThumbnails {
    /** Target longest-edge for a grid tile; keeps memory bounded for large photos/videos. */
    private const val TARGET_PX = 256

    /** Longest edge of the persisted encrypted thumbnail (board-mandated ~200px, APP-244). */
    const val STORED_THUMB_PX = 200

    /** JPEG quality of the persisted thumbnail — a few tens of KB per item. */
    private const val STORED_THUMB_QUALITY = 85

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

    // --- Persisted encrypted thumbnails (APP-244) --------------------------------------

    /**
     * Render the small JPEG that gets encrypted into the on-disk thumbnail cache, from a
     * *staged* item whose public original is still readable (hide-time, before the source
     * is deleted). Images/videos only; anything else (and any decode failure) → null, so
     * hiding never fails because a preview couldn't be made.
     */
    suspend fun sourceThumbJpeg(
        context: Context,
        staged: VaultItem,
    ): ByteArray? {
        val uri = staged.sourceUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        val isImage =
            staged.category == VaultCategory.PHOTOS || staged.mimeType?.startsWith("image/") == true
        val isVideo =
            staged.category == VaultCategory.VIDEOS || staged.mimeType?.startsWith("video/") == true
        if (!isImage && !isVideo) return null
        return withContext(Dispatchers.IO) {
            val bitmap =
                loadThumbnailViaResolver(context, uri)
                    ?: if (isImage) decodeSampledImage(context, uri) else videoFrame(context, uri)
            bitmap?.let(::toStoredJpeg)
        }
    }

    /** Decode a decrypted stored-thumb JPEG back into a grid bitmap. */
    fun decodeStoredThumb(jpeg: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()

    /** Compress an already-decoded backfill bitmap into the stored-thumb JPEG format. */
    fun toStoredJpeg(bitmap: Bitmap): ByteArray {
        val scaled = scaleDown(bitmap, STORED_THUMB_PX)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, STORED_THUMB_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        return out.toByteArray()
    }

    /** Backfill: a video frame from an already-decrypted temp file (no full-bytes array). */
    fun videoFrameFromFile(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Backfill: sampled decode of decrypted full-image bytes (grid-tile sized). */
    fun sampledBitmapFromBytes(bytes: ByteArray): Bitmap? = decodeSampledImage(bytes)

    /**
     * Re-derive a stored thumb rotated by [deltaDegrees] clockwise, from the existing
     * ~200px thumb JPEG itself — the W3-E rotate-persist propagation (W3-D §9 rule 2:
     * cheapest available source; the full-size blob is never decrypted for a tile).
     * Null on decode failure or a whole-turn delta (nothing to do).
     */
    fun rotatedStoredThumb(
        jpeg: ByteArray,
        deltaDegrees: Int,
    ): ByteArray? {
        val normalized = ((deltaDegrees % 360) + 360) % 360
        if (normalized == 0) return null
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val rotated = rotate(decoded, normalized)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, STORED_THUMB_QUALITY, out)
        if (rotated !== decoded) decoded.recycle()
        return out.toByteArray()
    }

    /** [bitmap] rotated [degrees] clockwise; returns [bitmap] itself for a 0° turn. */
    fun rotate(
        bitmap: Bitmap,
        degrees: Int,
    ): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** MediaStore's own thumbnail cache (API 29+) — cheapest hide-time source. */
    private fun loadThumbnailViaResolver(
        context: Context,
        uri: Uri,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(STORED_THUMB_PX, STORED_THUMB_PX), null)
        }.getOrNull()
    }

    /** Two-pass sampled decode straight off the source stream (content or file scheme). */
    private fun decodeSampledImage(
        context: Context,
        uri: Uri,
    ): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            val opts =
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, STORED_THUMB_PX)
                }
            openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()

    /** First decodable frame of a still-public video source. */
    private fun videoFrame(
        context: Context,
        uri: Uri,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun openStream(
        context: Context,
        uri: Uri,
    ): InputStream? =
        if (uri.scheme == "file") {
            uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }

    private fun scaleDown(
        bitmap: Bitmap,
        targetPx: Int,
    ): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= targetPx) return bitmap
        val scale = targetPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** Down-sample [bytes] to ~[TARGET_PX] on the longest edge so the full image never loads. */
    private fun decodeSampledImage(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX)
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        targetPx: Int,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfW >= targetPx && halfH >= targetPx) {
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
        return try {
            tmp.writeBytes(bytes)
            videoFrameFromFile(tmp.absolutePath)
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }
}
