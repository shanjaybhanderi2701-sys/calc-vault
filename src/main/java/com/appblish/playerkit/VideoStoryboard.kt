package com.appblish.playerkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The **scrub-preview storyboard** for the shared player (APP-419, forward-ported to the kit by
 * APP-438): a strip of small, evenly-spaced frames (YouTube/MX "hover the seekbar" style) packed
 * into ONE small JPEG sprite-sheet. This object is the *generic* half — pure sprite-sheet math plus
 * Bitmap [encode]/[decode] and a cropable [Strip]. It never learns where the frames came from or how
 * they were acquired: a consumer extracts frames from its own source (a plain `content://`, a sealed
 * blob, …), calls [encode], and hands the resulting bytes back through a [StoryboardSource]. The kit
 * decodes and caches (see [VideoStoryboardCache]); frame acquisition stays app-side.
 *
 * On-disk container ([encode]/[decode]) — a tiny self-describing header then one JPEG sheet whose
 * frames are laid out left→right, all the same size:
 *
 * ```
 * "PKSB1" (5 bytes) | frameCount:int | frameWidth:int | frameHeight:int | jpegSheet:bytes
 * ```
 *
 * Everything is best-effort: any decode/extract failure yields null so a video without a strip
 * simply shows the time-code scrub bubble rather than crashing.
 */
object VideoStoryboard {
    /** Longest edge of a single storyboard frame — small: a scrub bubble, not a poster. */
    const val FRAME_MAX_PX = 160

    /** Upper bound on frames in a strip so a long video's sheet stays a few tens of KB. */
    const val MAX_FRAMES = 16

    /** Lower bound so even a short clip gets a usable scrub preview. */
    const val MIN_FRAMES = 4

    /** Aim for roughly one frame per this many ms, clamped to [[MIN_FRAMES], [MAX_FRAMES]]. */
    private const val MS_PER_FRAME_TARGET = 4_000L

    private const val SHEET_QUALITY = 70
    private val MAGIC = "PKSB1".toByteArray(Charsets.US_ASCII)

    /**
     * The frame index within a [frameCount]-frame strip that best represents [fraction] of the
     * timeline. Pure arithmetic (no Android types) so the scrub→frame mapping is unit-testable.
     * Frames sample the closed interval `[0, 1]`, so fraction 0 → first frame and 1 → last.
     */
    fun frameIndexFor(
        fraction: Float,
        frameCount: Int,
    ): Int {
        if (frameCount <= 1) return 0
        val clamped = fraction.coerceIn(0f, 1f)
        return Math.round(clamped * (frameCount - 1)).coerceIn(0, frameCount - 1)
    }

    /**
     * How many frames to sample for a clip of [durationMs] — ~one per [MS_PER_FRAME_TARGET],
     * clamped to [[MIN_FRAMES], [MAX_FRAMES]]. A zero/unknown duration falls back to [MIN_FRAMES].
     */
    fun frameCountFor(durationMs: Long): Int {
        if (durationMs <= 0L) return MIN_FRAMES
        val byDuration = (durationMs / MS_PER_FRAME_TARGET).toInt() + 1
        return byDuration.coerceIn(MIN_FRAMES, MAX_FRAMES)
    }

    /**
     * The presentation time (µs) to grab the [index]-th of [frameCount] frames from a clip of
     * [durationMs]. Evenly spaced across `[0, durationMs]`; safe for frameCount == 1.
     */
    fun frameTimeUs(
        index: Int,
        frameCount: Int,
        durationMs: Long,
    ): Long {
        if (frameCount <= 1 || durationMs <= 0L) return 0L
        val i = index.coerceIn(0, frameCount - 1)
        return durationMs * 1000L * i / (frameCount - 1)
    }

    // --- On-disk container -------------------------------------------------------------------

    /**
     * Scale every extracted frame to a common size: [FRAME_MAX_PX] on the longest edge of the
     * FIRST frame (all frames of one video share dimensions), so the sheet is a clean grid. A
     * consumer calls this on its raw [MediaMetadataRetriever]-style frames before [encode]. The
     * input frames are recycled once scaled.
     */
    fun scaleToStrip(raw: List<Bitmap>): List<Bitmap> {
        val src = raw.first()
        val longest = maxOf(src.width, src.height).coerceAtLeast(1)
        val scale = if (longest <= FRAME_MAX_PX) 1f else FRAME_MAX_PX.toFloat() / longest
        val fw = (src.width * scale).toInt().coerceAtLeast(1)
        val fh = (src.height * scale).toInt().coerceAtLeast(1)
        return raw.map { frame ->
            val scaled = Bitmap.createScaledBitmap(frame, fw, fh, true)
            if (scaled !== frame) frame.recycle()
            scaled
        }
    }

    /**
     * Pack [frames] (all scaled to a common size — [scaleToStrip] does this) into the storyboard
     * container bytes: header + one horizontal JPEG sheet. Null if [frames] is empty or the sheet
     * can't be allocated/compressed.
     */
    fun encode(frames: List<Bitmap>): ByteArray? {
        if (frames.isEmpty()) return null
        val fw = frames.first().width
        val fh = frames.first().height
        if (fw <= 0 || fh <= 0) return null
        val sheet =
            runCatching { Bitmap.createBitmap(fw * frames.size, fh, Bitmap.Config.ARGB_8888) }
                .getOrNull() ?: return null
        val canvas = Canvas(sheet)
        frames.forEachIndexed { i, frame ->
            // Draw each frame into its slot, letterboxing a stray odd-sized frame into fw×fh.
            canvas.drawBitmap(frame, null, Rect(i * fw, 0, i * fw + fw, fh), null)
        }
        val jpeg = ByteArrayOutputStream()
        val ok = sheet.compress(Bitmap.CompressFormat.JPEG, SHEET_QUALITY, jpeg)
        sheet.recycle()
        if (!ok) return null
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeInt(frames.size)
            d.writeInt(fw)
            d.writeInt(fh)
            d.write(jpeg.toByteArray())
        }
        return out.toByteArray()
    }

    /** Decode container [bytes] into a cropable [Strip], or null if malformed/undecodable. */
    fun decode(bytes: ByteArray): Strip? {
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val magic = ByteArray(MAGIC.size)
                d.readFully(magic)
                if (!magic.contentEquals(MAGIC)) return null
                val frameCount = d.readInt()
                val frameWidth = d.readInt()
                val frameHeight = d.readInt()
                if (frameCount <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
                val sheetBytes = d.readBytes()
                val sheet = BitmapFactory.decodeByteArray(sheetBytes, 0, sheetBytes.size) ?: return null
                Strip(sheet, frameCount, frameWidth, frameHeight)
            }
        }.getOrNull()
    }

    /**
     * A decoded storyboard: the full sprite-sheet [Bitmap] held once, from which [frameAt] crops
     * the nearest sub-frame per scrub position with no re-decode.
     */
    class Strip(
        private val sheet: Bitmap,
        val frameCount: Int,
        val frameWidth: Int,
        val frameHeight: Int,
    ) {
        /** Approximate decoded byte size (for the LRU byte budget). */
        val byteSize: Int = sheet.width * sheet.height * 4

        /** The frame nearest [fraction] of the timeline, as an [ImageBitmap] for Compose. */
        fun frameAt(fraction: Float): ImageBitmap {
            val index = frameIndexFor(fraction, frameCount)
            val x = (index * frameWidth).coerceIn(0, (sheet.width - frameWidth).coerceAtLeast(0))
            val cropW = frameWidth.coerceAtMost(sheet.width - x)
            val cropH = frameHeight.coerceAtMost(sheet.height)
            return Bitmap.createBitmap(sheet, x, 0, cropW.coerceAtLeast(1), cropH.coerceAtLeast(1)).asImageBitmap()
        }
    }
}
